"""
Export the fine-tuned XLM-RoBERTa NER model to INT8 TFLite.

Input:  outputs/xlm_roberta_ner/
Output: tflite_exports/ner_model.tflite
        tflite_exports/ner_vocab.txt

The vocabulary file uses the SentencePiece serialised format expected by
WordPieceTokenizer in the Android module. XLM-RoBERTa uses BPE, so we
export the raw vocab JSON and the Android code must be adapted — or replace
with a DistilBERT-NER checkpoint (which uses WordPiece) for a drop-in fit.
"""

from pathlib import Path

import numpy as np
from transformers import AutoTokenizer

CHECKPOINT = Path("outputs/xlm_roberta_ner")
EXPORT_DIR = Path("tflite_exports")
EXPORT_DIR.mkdir(parents=True, exist_ok=True)
SEQ_LEN = 128


def export():
    try:
        from optimum.exporters.tflite import main_export
        main_export(
            model_name_or_path=str(CHECKPOINT),
            output=str(EXPORT_DIR / "ner_fp32"),
            task="token-classification",
            sequence_length=SEQ_LEN,
        )
        _quantize_int8(EXPORT_DIR / "ner_fp32" / "model.tflite")
    except Exception as e:
        print(f"optimum export failed ({e}); falling back to manual ONNX export")
        _manual_export()


def _quantize_int8(fp32_path: Path):
    import tensorflow as tf

    tokenizer = AutoTokenizer.from_pretrained(str(CHECKPOINT))

    def representative_dataset():
        sentences = [
            "John Smith lives in New York and works at Google",
            "Alice Johnson was born in Paris France",
            "Contact support@example.com or call 555-1234",
            "Dr. Maria Gonzalez prescribed medication",
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
    out = EXPORT_DIR / "ner_model.tflite"
    out.write_bytes(tflite_model)
    print(f"INT8 NER TFLite saved: {out} ({len(tflite_model) / 1024:.1f} KB)")


def _manual_export():
    import torch
    from transformers import AutoModelForTokenClassification

    model = AutoModelForTokenClassification.from_pretrained(str(CHECKPOINT))
    model.eval()
    tokenizer = AutoTokenizer.from_pretrained(str(CHECKPOINT))

    dummy = tokenizer("hello world John", max_length=SEQ_LEN, padding="max_length",
                      truncation=True, return_tensors="pt")
    onnx_path = EXPORT_DIR / "ner_model.onnx"

    torch.onnx.export(
        model,
        (dummy["input_ids"], dummy["attention_mask"]),
        str(onnx_path),
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch"},
            "attention_mask": {0: "batch"},
            "logits": {0: "batch"},
        },
        opset_version=14,
    )
    print(f"ONNX NER model saved: {onnx_path}")


def export_vocab():
    tokenizer = AutoTokenizer.from_pretrained(str(CHECKPOINT))
    vocab_path = EXPORT_DIR / "ner_vocab.txt"
    # For WordPiece-compatible vocab (works with NerModelDetector as-is):
    if hasattr(tokenizer, "vocab"):
        vocab = sorted(tokenizer.vocab.items(), key=lambda x: x[1])
        vocab_path.write_text("\n".join(tok for tok, _ in vocab), encoding="utf-8")
    else:
        # BPE (XLM-RoBERTa): write raw vocab for custom tokenizer adaptation
        tokenizer.save_vocabulary(str(EXPORT_DIR))
        print("BPE vocab saved — update WordPieceTokenizer in NerModelDetector to use BPE.")
    print(f"Vocab: {vocab_path}")


if __name__ == "__main__":
    export()
    export_vocab()
    print(f"\nCopy to Android assets:")
    print(f"  cp {EXPORT_DIR}/ner_model.tflite ../privacy-router/src/main/assets/")
    print(f"  cp {EXPORT_DIR}/ner_vocab.txt ../privacy-router/src/main/assets/")
