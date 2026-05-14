"""
Export the fine-tuned MobileBERT classifier to INT8 TFLite.

Input:  outputs/mobilebert_classifier/  (HuggingFace checkpoint)
Output: tflite_exports/mobilebert_classifier.tflite
        tflite_exports/mobilebert_vocab.txt   (WordPiece vocabulary)

Copy both files into privacy-router/src/main/assets/ for on-device use.
"""

import shutil
from pathlib import Path

import numpy as np
from transformers import AutoTokenizer

CHECKPOINT = Path("outputs/mobilebert_classifier")
EXPORT_DIR = Path("tflite_exports")
EXPORT_DIR.mkdir(parents=True, exist_ok=True)

SEQ_LEN = 64


def export():
    try:
        from optimum.exporters.tflite import main_export
    except ImportError:
        print("optimum[exporters-tf] not installed — falling back to manual export")
        _manual_export()
        return

    main_export(
        model_name_or_path=str(CHECKPOINT),
        output=str(EXPORT_DIR / "mobilebert_classifier_fp32"),
        task="text-classification",
        sequence_length=SEQ_LEN,
    )
    _quantize_int8(EXPORT_DIR / "mobilebert_classifier_fp32" / "model.tflite")


def _quantize_int8(fp32_path: Path):
    import tensorflow as tf

    tokenizer = AutoTokenizer.from_pretrained(str(CHECKPOINT))

    def representative_dataset():
        sentences = [
            "set a timer for 5 minutes",
            "my doctor prescribed medication",
            "what is the capital of France",
            "call my mom",
            "transfer money to Alice",
        ] * 20
        for sent in sentences:
            enc = tokenizer(
                sent, max_length=SEQ_LEN, padding="max_length",
                truncation=True, return_tensors="np",
            )
            yield [enc["input_ids"].astype(np.int32), enc["attention_mask"].astype(np.int32)]

    converter = tf.lite.TFLiteConverter.from_saved_model(str(fp32_path.parent))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int32
    converter.inference_output_type = tf.float32

    tflite_model = converter.convert()
    out = EXPORT_DIR / "mobilebert_classifier.tflite"
    out.write_bytes(tflite_model)
    print(f"INT8 TFLite model saved: {out} ({len(tflite_model) / 1024:.1f} KB)")


def _manual_export():
    """Fallback: PT → ONNX → TFLite via onnx-tf (slower, less optimised)."""
    import torch
    from transformers import AutoModelForSequenceClassification

    model = AutoModelForSequenceClassification.from_pretrained(str(CHECKPOINT))
    model.eval()
    tokenizer = AutoTokenizer.from_pretrained(str(CHECKPOINT))

    dummy = tokenizer("hello world", max_length=SEQ_LEN, padding="max_length",
                      truncation=True, return_tensors="pt")
    onnx_path = EXPORT_DIR / "mobilebert_classifier.onnx"

    torch.onnx.export(
        model,
        (dummy["input_ids"], dummy["attention_mask"]),
        str(onnx_path),
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={"input_ids": {0: "batch"}, "attention_mask": {0: "batch"}},
        opset_version=14,
    )
    print(f"ONNX saved: {onnx_path}")
    print("Convert ONNX→TFLite with: https://github.com/onnx/onnx-tensorflow")


def copy_vocab():
    tokenizer = AutoTokenizer.from_pretrained(str(CHECKPOINT))
    vocab_file = EXPORT_DIR / "mobilebert_vocab.txt"
    vocab = sorted(tokenizer.vocab.items(), key=lambda x: x[1])
    vocab_file.write_text("\n".join(tok for tok, _ in vocab), encoding="utf-8")
    print(f"Vocab saved: {vocab_file} ({len(vocab)} tokens)")


if __name__ == "__main__":
    export()
    copy_vocab()
    print(f"\nCopy to Android assets:")
    print(f"  cp {EXPORT_DIR}/mobilebert_classifier.tflite ../privacy-router/src/main/assets/")
    print(f"  cp {EXPORT_DIR}/mobilebert_vocab.txt ../privacy-router/src/main/assets/")
