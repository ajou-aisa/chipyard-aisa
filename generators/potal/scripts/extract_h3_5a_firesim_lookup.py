#!/usr/bin/env python3
"""Extract the archived FireSim lookup/address cone unchanged into a directed SV test."""
from hashlib import sha256
from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: extract_h3_5a_firesim_lookup.py FireSim-generated.sv output.sv")
artifact = Path(sys.argv[1])
output = Path(sys.argv[2])
rtl = artifact.read_text()
controller = rtl.split("module ExecuteController(", 1)[1].split("\nendmodule", 1)[0]
start = controller.index("  wire  _GEN_1401 =")
end = controller.index("  wire [13:0] indexedDAddress_data =", start)
cone = controller[start:end]
header = """module H3FireSimLookup;
  reg preload_cmd_place;
  wire _a_address_place_T = ~preload_cmd_place;
  wire [1:0] _GEN_104 = {1'b0, preload_cmd_place};
  reg [4:0] indexSlot;
"""
for command in range(3):
    header += f"  reg cmd_q_io_deq_bits_{command}_potal_weightBufferId = 0;\n"
    header += f"  reg [15:0] cmd_q_io_deq_bits_{command}_potal_indexedJTile;\n"
for buffer in range(2):
    header += f"  wire [31:0] indexDescriptors_{buffer}_jTiles = 2;\n"
    header += f"  wire [31:0] indexDescriptors_{buffer}_bBase = 8064;\n"
    for slot in range(32):
        header += f"  reg [31:0] indexTables_{buffer}_{slot};\n"
body = """
  integer mapping, j, s, errors, total;
  integer expectedSource, expectedAddress;
  initial begin
    total = 0;
    for (mapping = 0; mapping < 3; mapping = mapping + 1) begin
      errors = 0;
"""
for slot in range(32):
    value = f"(mapping == 1 ? ({slot} == 0 ? 11 : 0) : mapping == 2 ? 0 : {slot})" if slot < 16 else "0"
    body += f"      indexTables_0_{slot} = {value};\n"
    body += f"      indexTables_1_{slot} = 0;\n"
body += """
      for (j = 0; j < 2; j = j + 1) begin
        preload_cmd_place = j;
        cmd_q_io_deq_bits_0_potal_indexedJTile = j;
        cmd_q_io_deq_bits_1_potal_indexedJTile = j;
        cmd_q_io_deq_bits_2_potal_indexedJTile = j;
        for (s = 15; s >= 0; s = s - 1) begin
          indexSlot = s;
          #1;
          expectedSource = mapping == 1 ? (s == 0 ? 11 : 0) : mapping == 2 ? 0 : s;
          expectedAddress = 8064 + ((expectedSource / 16) * 2 + j) * 16 + expectedSource % 16;
          if (_GEN_545 != expectedSource || indexedSPRow != expectedAddress) begin
            if (errors == 0 || s == 1 || s == 0)
              $display("H3_ARTIFACT_DIFF mapping=%0d J=%0d place=%0d slot=%0d expectedSource=%0d actualSource=%0d expectedSP=%0d actualSP=%0d", mapping, j, preload_cmd_place, s, expectedSource, _GEN_545, expectedAddress, indexedSPRow);
            errors = errors + 1;
          end
        end
      end
      total = total + errors;
      $display("H3_ARTIFACT_RESULT mapping=%0d badWeightRows=%0d/32", mapping, errors);
    end
    if (total != 0) $fatal(1, "Archived FireSim lookup differs from independent Index/address contract");
    $finish;
  end
endmodule
"""
output.write_text(header + cone + body)
print(f"artifact_sha256={sha256(artifact.read_bytes()).hexdigest()}")
print(f"extracted_lines={len(cone.splitlines())} output={output}")
