"""
Stage 2 NER fine-tuning: XLM-RoBERTa-base on ai4privacy/pii-masking-openpii-1m.

Languages: English, Ukrainian, Portuguese (configurable via LANGUAGES env var).

BIO tag set (must match NerModelDetector.TAGS in the Android module):
  O, B-PER, I-PER, B-LOC, I-LOC, B-ORG, I-ORG, B-MISC, I-MISC

Output: outputs/xlm_roberta_ner/
"""

import os
from pathlib import Path

import numpy as np
from datasets import concatenate_datasets, load_dataset
from seqeval.metrics import classification_report as seq_report
from transformers import (
    AutoModelForTokenClassification,
    AutoTokenizer,
    DataCollatorForTokenClassification,
    Trainer,
    TrainingArguments,
)

LANGUAGES = os.getenv("LANGUAGES", "en,uk,pt").split(",")

LABEL_LIST = ["O", "B-PER", "I-PER", "B-LOC", "I-LOC", "B-ORG", "I-ORG", "B-MISC", "I-MISC"]
LABEL2ID = {l: i for i, l in enumerate(LABEL_LIST)}
ID2LABEL = {i: l for i, l in enumerate(LABEL_LIST)}

# Map ai4privacy span types → BIO entity types
SPAN_TO_ENTITY = {
    "NAME": "PER", "PERSON": "PER", "FIRSTNAME": "PER", "LASTNAME": "PER",
    "CITY": "LOC", "COUNTRY": "LOC", "LOCATION": "LOC", "ADDRESS": "LOC",
    "STATE": "LOC", "ZIPCODE": "LOC", "COUNTY": "LOC",
    "ORGANIZATION": "ORG", "COMPANY": "ORG", "EMPLOYER": "ORG",
    "EMAIL": "MISC", "PHONE": "MISC", "SSN": "MISC", "CREDITCARDNUMBER": "MISC",
    "IBAN": "MISC", "IPADDRESS": "MISC", "USERNAME": "MISC", "PASSWORD": "MISC",
    "DATE": "MISC", "TIME": "MISC", "DOB": "MISC", "AGE": "MISC",
    "GENDER": "MISC", "JOBAREA": "MISC", "JOBTITLE": "MISC",
    "MEDICALCONDITION": "MISC", "MIDDLENAME": "PER", "SUFFIX": "PER",
    "URL": "MISC", "VEHICLEVIN": "MISC", "VEHICLEVRM": "MISC",
}


def load_and_align(lang: str, tokenizer, max_samples: int = 50_000):
    ds = load_dataset(
        "ai4privacy/pii-masking-openpii-1m",
        split=f"train[:{max_samples}]",
        trust_remote_code=True,
    )
    if "language" in ds.column_names:
        ds = ds.filter(lambda x: x.get("language", "en") == lang)

    def tokenize_and_align(examples):
        tokenized = tokenizer(
            examples["source_text"],
            truncation=True,
            max_length=128,
            is_split_into_words=False,
        )
        all_labels = []
        for i, spans in enumerate(examples.get("privacy_mask", [[]])):
            text = examples["source_text"][i]
            word_ids = tokenized.word_ids(batch_index=i)
            char_to_label = ["O"] * len(text)
            for span in spans if isinstance(spans, list) else []:
                span_type = span.get("label", "").upper().replace(" ", "")
                entity = SPAN_TO_ENTITY.get(span_type)
                if entity is None:
                    continue
                start, end = int(span.get("start", 0)), int(span.get("end", 0))
                for ci in range(start, min(end, len(char_to_label))):
                    char_to_label[ci] = ("B-" if ci == start else "I-") + entity

            # Map char labels → token labels via offset mapping
            offsets = tokenized.encodings[i].offsets
            token_labels = []
            prev_word = None
            for j, (char_start, char_end) in enumerate(offsets):
                if char_start == char_end:
                    token_labels.append(-100)
                    continue
                mid = (char_start + char_end) // 2
                lbl = char_to_label[mid] if mid < len(char_to_label) else "O"
                token_labels.append(LABEL2ID.get(lbl, 0))
                prev_word = word_ids[j]

            all_labels.append(token_labels)

        tokenized["labels"] = all_labels
        return tokenized

    cols_to_remove = [c for c in ds.column_names if c not in ("source_text", "privacy_mask")]
    return ds.map(tokenize_and_align, batched=True, remove_columns=cols_to_remove)


def compute_metrics(eval_pred):
    logits, labels = eval_pred
    preds = np.argmax(logits, axis=-1)

    true_seqs, pred_seqs = [], []
    for pred_row, label_row in zip(preds, labels):
        true_seq, pred_seq = [], []
        for p, l in zip(pred_row, label_row):
            if l == -100:
                continue
            true_seq.append(ID2LABEL[l])
            pred_seq.append(ID2LABEL[p])
        true_seqs.append(true_seq)
        pred_seqs.append(pred_seq)

    report = seq_report(true_seqs, pred_seqs, output_dict=True, zero_division=0)
    return {
        "f1_micro": report.get("micro avg", {}).get("f1-score", 0.0),
        "f1_macro": report.get("macro avg", {}).get("f1-score", 0.0),
    }


def main():
    model_name = "xlm-roberta-base"
    output_dir = Path("outputs/xlm_roberta_ner")
    output_dir.mkdir(parents=True, exist_ok=True)

    tokenizer = AutoTokenizer.from_pretrained(model_name)

    datasets = [load_and_align(lang, tokenizer) for lang in LANGUAGES]
    combined = concatenate_datasets(datasets).shuffle(seed=42)
    split = combined.train_test_split(test_size=0.05, seed=42)

    model = AutoModelForTokenClassification.from_pretrained(
        model_name,
        num_labels=len(LABEL_LIST),
        id2label=ID2LABEL,
        label2id=LABEL2ID,
    )

    import torch
    args = TrainingArguments(
        output_dir=str(output_dir),
        num_train_epochs=3,
        per_device_train_batch_size=16,
        per_device_eval_batch_size=32,
        learning_rate=2e-5,
        warmup_ratio=0.06,
        weight_decay=0.01,
        evaluation_strategy="epoch",
        save_strategy="epoch",
        load_best_model_at_end=True,
        metric_for_best_model="f1_micro",
        fp16=torch.cuda.is_available(),
        report_to="none",
        logging_steps=100,
    )

    trainer = Trainer(
        model=model,
        args=args,
        train_dataset=split["train"],
        eval_dataset=split["test"],
        tokenizer=tokenizer,
        data_collator=DataCollatorForTokenClassification(tokenizer),
        compute_metrics=compute_metrics,
    )

    trainer.train()
    trainer.save_model(str(output_dir))
    tokenizer.save_pretrained(str(output_dir))
    print(f"NER model saved to {output_dir}")


if __name__ == "__main__":
    main()
