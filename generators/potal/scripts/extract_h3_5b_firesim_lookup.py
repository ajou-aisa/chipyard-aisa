#!/usr/bin/env python3
"""Exercise the actual generated ExecuteController lookup/address cone for H3-5B."""
from hashlib import sha256
from pathlib import Path
import re
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: extract_h3_5b_firesim_lookup.py FireSim-generated.sv output.sv")
artifact, output = map(Path, sys.argv[1:])
rtl = artifact.read_text()
controller = rtl.split("module ExecuteController(", 1)[1].split("\nendmodule", 1)[0]
declarations = {match[1]: match[0] for match in re.finditer(
    r"^  wire\s+(?:\[[^\]]+\]\s*)?(\w+)\s*=\s*[^;]+;", controller, re.MULTILINE)}
terminal = re.compile(r"preload_cmd_place|indexSlot|cmd_q_io_deq_bits_[012]_potal_(?:weightBufferId|indexedJTile)|indexTables_[01]_\d+|indexDescriptors_[01]_(?:jTiles|bBase)")
visited: set[str] = set()
cone: list[str] = []


def emit(name: str) -> None:
    """Copy only original declarations, recursively following their dependencies."""
    if name in visited or terminal.fullmatch(name):
        return
    visited.add(name)
    declaration = declarations[name]
    expression = declaration.split("=", 1)[1]
    expression = re.sub(r"\d+'[sS]?[hHbBdDoO][0-9a-fA-FxXzZ_]+", "", expression)
    for dependency in re.findall(r"\b[A-Za-z_]\w*", expression):
        emit(dependency)
    cone.append(declaration)


for root in ("sourceKTile", "sourceKRow", "indexedSPRow"):
    emit(root)
header = """module H3FireSimLookup;
  reg preload_cmd_place;
  reg [4:0] indexSlot;
"""
for command in range(3):
    header += f"  reg cmd_q_io_deq_bits_{command}_potal_weightBufferId;\n"
    header += f"  reg [15:0] cmd_q_io_deq_bits_{command}_potal_indexedJTile;\n"
for buffer in range(2):
    header += f"  wire [31:0] indexDescriptors_{buffer}_jTiles = 2;\n"
    header += f"  wire [31:0] indexDescriptors_{buffer}_bBase = {8064 + buffer * 8192};\n"
    for slot in range(32):
        header += f"  reg [31:0] indexTables_{buffer}_{slot};\n"
body = """
  wire [31:0] actualSource = {sourceKTile, sourceKRow};
  integer bufferId, place, slot, j, focused, directed, errors, wantSource, wantSP;
  task check;
    begin
      #1;
      wantSP = 8064 + bufferId * 8192 + ((wantSource / 16) * 2 + j) * 16 + wantSource % 16;
      if (focused == 128)
        $display("H3_5B_POINT buffer=%0d J=%0d slot=%0d source=%0d SP=%0d expectedK=%0d expectedSP=%0d", bufferId, j, slot, actualSource, indexedSPRow, wantSource, wantSP);
      if (actualSource != wantSource || indexedSPRow != wantSP) begin
        if (errors < 6)
          $display("H3_5B_DIFF buffer=%0d place=%0d J=%0d slot=%0d wantK=%0d gotK=%0d wantSP=%0d gotSP=%0d", bufferId, place, j, slot, wantSource, actualSource, wantSP, indexedSPRow);
        errors = errors + 1;
      end
    end
  endtask
  task selectMetadata;
    begin
"""
for command in range(3):
    body += f"      cmd_q_io_deq_bits_{command}_potal_weightBufferId = (place == {command}) ? bufferId : !bufferId;\n"
    body += f"      cmd_q_io_deq_bits_{command}_potal_indexedJTile = (place == {command}) ? j : !j;\n"
body += """      preload_cmd_place = place;
      indexSlot = slot;
    end
  endtask
  initial begin
    focused = 0; directed = 0; errors = 0;
"""
for buffer in range(2):
    for slot in range(32):
        body += f"    indexTables_{buffer}_{slot} = {buffer * 32 + slot};\n"
body += """
    // Distinct table contents and opposing unselected command metadata detect buffer aliasing.
    for (bufferId = 0; bufferId < 2; bufferId = bufferId + 1)
      for (place = 0; place < 2; place = place + 1)
        for (slot = 31; slot >= 0; slot = slot - 1) begin
          j = place; wantSource = bufferId * 32 + slot;
          selectMetadata(); check(); focused = focused + 1;
        end
"""
for buffer in range(2):
    for slot in range(32):
        body += f"    indexTables_{buffer}_{slot} = {slot};\n"
body += """
    for (bufferId = 0; bufferId < 2; bufferId = bufferId + 1) begin
      place = 0; j = 0; slot = 15; wantSource = 15;
      selectMetadata(); check(); directed = directed + 1;
      place = 1; j = 1; slot = 15; wantSource = 15;
      selectMetadata(); check(); directed = directed + 1;
      place = 0; j = 0; slot = 5; wantSource = 5;
      selectMetadata(); check(); directed = directed + 1;
      indexTables_0_0 = 11; indexTables_1_0 = 11;
      place = 0; j = 0; slot = 0; wantSource = 11;
      selectMetadata(); check(); directed = directed + 1;
      place = 1; j = 1; slot = 0; wantSource = 11;
      selectMetadata(); check(); directed = directed + 1;
      indexTables_0_0 = 0; indexTables_1_0 = 0;
    end
    $display("H3_5B_GENERATED focused=%0d directed=%0d errors=%0d", focused, directed, errors);
    if (errors != 0) $fatal(1, "Generated FireSim Index/address contract failed");
    $finish;
  end
endmodule
"""
output.write_text(header + "\n".join(cone) + body)
print(f"artifact={artifact} sha256={sha256(artifact.read_bytes()).hexdigest()}")
print(f"copied_declarations={len(cone)} output={output}")
