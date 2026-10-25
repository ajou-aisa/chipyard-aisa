#!/usr/bin/env python3
"""Check the H3-5A trace in request order, including SRAM backpressure and Mesh latency."""
from collections import deque
import csv
from enum import Enum
from pathlib import Path
import re
import sys
from typing_extensions import assert_never


class Kind(Enum):
    LOOP = "H3_LOOP"
    EX = "H3_EX"
    REQ = "H3_REQ"
    RESP = "H3_RESP"
    MESH = "H3_MESH"


def verify(log: Path, output: Path) -> None:
    mappings = {"K_TAG_B": list(range(16)), "SLOT0_REMAP11": [11] + [0] * 15,
                "ALL_SLOTS_K0": [0] * 16}
    pending: list[deque[dict[str, str]]] = [deque() for _ in range(4)]
    weights: deque[dict[str, str]] = deque()
    metadata: dict[int, dict[str, str]] = {}
    executions: dict[int, dict[str, str]] = {}
    counts = dict.fromkeys(mappings, 0)
    completed: list[dict[str, str]] = []
    case = ""
    stalls = 0
    text = log.read_text()
    uart = re.sub(r"C\d+:.*\n", "", text)
    uart = re.sub(r"\[H3_\w+\].*\n", "", uart)
    case_names = re.findall(r"H3_5A BEGIN (\w+)", uart)
    assert case_names == list(mappings), ("case order", case_names)
    assert "H3_5A PASS errors=0" in uart, "Directed ACC regression did not finish successfully"
    for line in text.splitlines():
        event = re.search(r"\[(H3_\w+)\](.*)", line)
        if event is None:
            continue
        kind = Kind(event[1])
        fields = dict(re.findall(r"(\w+)=\s*([0-9a-fA-F]+)", event[2]))
        match kind:
            case Kind.LOOP:
                metadata[int(fields["meta"], 16)] = fields
            case Kind.EX:
                # Idle command-queue bits do not identify an accepted PRELOAD.
                if int(fields["meta"], 16) in metadata:
                    executions[int(fields["t"])] = fields
                    stalls += fields["cqready"] == "0"
            case Kind.REQ:
                if fields["r"] == "0":
                    continue
                bank, tick = int(fields["bank"]), int(fields["t"])
                request = fields.copy()
                if bank == 1 and tick in executions:
                    ex = executions[tick]
                    case = case_names[sum(counts.values()) // 32]
                    ordinal = counts[case]
                    j, slot = ordinal // 16, 15 - ordinal % 16
                    source = mappings[case][slot]
                    expected = 8064 + ((source // 16) * 2 + j) * 16 + source % 16
                    assert int(ex["kt"]) == 0 and int(ex["jt"]) == j, ("metadata K/J", case, ex)
                    assert int(ex["dc"]) == 15 - slot and int(ex["row"]) == slot, ("reverse row", case, ex)
                    assert int(ex["slot"]) == slot and int(ex["source"]) == source, ("stage 1 Index", case, ex, source)
                    assert int(ex["sp"]) == expected, ("stage 2 translation", case, ex, expected)
                    assert bank * 4096 + int(fields["addr"]) == expected, ("stage 2 SRAM request", case, fields, expected)
                    request.update(case=case, j=str(j), slot=str(slot), source=str(source), sp=str(expected),
                                   loop=metadata[int(ex["meta"], 16)]["loop"])
                    counts[case] += 1
                pending[bank].append(request)
                if bank == 1:
                    weights.append(request)
            case Kind.RESP:
                bank = int(fields["bank"])
                assert pending[bank], ("response without accepted request", fields)
                request = pending[bank][0]
                if "case" in request:
                    offset = bank * 4096 + int(request["addr"]) - 8064
                    physical_k = (offset // 32) * 16 + offset % 16
                    expected_word = int.from_bytes(bytes([physical_k + 1]) * 16, "little")
                    assert int(fields["data"], 16) == expected_word, ("stage 3 SRAM data", request, fields)
                if fields["r"] == "1":
                    request.update(resp_t=fields["t"], resp_data=fields["data"])
                    pending[bank].popleft()
            case Kind.MESH:
                if fields["garbage"] == "1" or fields["v"] == "0" or fields["r"] == "0":
                    continue
                assert int(fields["bank"]) == 1 and weights, ("unexpected Weight bank/transfer", fields)
                request = weights.popleft()
                assert "resp_data" in request, ("stage 4 Mesh before consumed response", request, fields)
                assert int(fields["data"], 16) == int(request["resp_data"], 16), ("stage 4 Mesh lanes", request, fields)
                if "case" in request:
                    request.update(mesh_t=fields["t"], mesh_data=fields["data"])
                    completed.append(request)
            case unreachable:
                assert_never(unreachable)
    assert counts == dict.fromkeys(mappings, 32), ("missing indexed requests", counts)
    assert len(completed) == 96 and not weights and not any(pending), "Undrained request/response/Mesh queues"
    columns = ["case", "loop", "j", "slot", "source", "sp", "bank", "addr", "t", "resp_t", "mesh_t", "mesh_data"]
    with output.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, columns, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(completed)
    latencies = [int(row["resp_t"]) - int(row["t"]) for row in completed]
    print(f"H3_TRACE PASS indexed_rows={len(completed)} lanes={len(completed) * 16} "
          f"response_latency={min(latencies)}..{max(latencies)} cycles indexed_queue_stalls={stalls}")
    print(f"Matched rows: {output}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: check_h3_5a_trace.py trace.log matched.csv")
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
