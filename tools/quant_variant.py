#!/usr/bin/env python3
"""Experiment: dynamic INT8 of the fp32 encoder restricted to chosen op types.
Usage: quant_variant.py FP32_DIR OUT_DIR OP[,OP...]"""
import os, sys, onnx
from onnxruntime.quantization import QuantType, quantize_dynamic
src, dst, ops = sys.argv[1], sys.argv[2], sys.argv[3].split(",")
os.makedirs(dst, exist_ok=True)
out = os.path.join(dst, "encoder.int8.onnx")
quantize_dynamic(model_input=os.path.join(src, "encoder.onnx"), model_output=out,
                 weight_type=QuantType.QUInt8, op_types_to_quantize=ops, use_external_data_format=True)
m = onnx.load(out)  # fold external data back into one file where it fits, keep metadata from the source
src_meta = {p.key: p.value for p in onnx.load(os.path.join(src, "encoder.onnx"), load_external_data=False).metadata_props}
del m.metadata_props[:]
for k, v in src_meta.items():
    p = m.metadata_props.add(); p.key = k; p.value = v
for f in os.listdir(dst):
    os.remove(os.path.join(dst, f))
try:
    onnx.save(m, out)
except ValueError:
    onnx.save(m, out, save_as_external_data=True, all_tensors_to_one_file=True, location="encoder.int8.weights")
print({f: os.path.getsize(os.path.join(dst, f)) for f in os.listdir(dst)})
