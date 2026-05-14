"""
Stage 1 classifier fine-tuning: MobileBERT on CLINC150 → 5 privacy-router labels.

Label mapping:
  DEVICE_ACTION    — oos + {alarm, timer, calendar, phone, calculator, ...}
  PERSONAL_QUERY   — {health, finance, home, ...}
  FACTUAL_QUERY    — {travel, weather, geography, science, ...}
  CONVERSATIONAL   — {small_talk, meta, ...}
  AMBIGUOUS        — remaining / OOS intents

Output: outputs/mobilebert_classifier/  (HuggingFace checkpoint)
"""

import os
from pathlib import Path

import numpy as np
import torch
from datasets import load_dataset
from sklearn.metrics import classification_report
from transformers import (
    AutoModelForSequenceClassification,
    AutoTokenizer,
    DataCollatorWithPadding,
    Trainer,
    TrainingArguments,
)

# ── Label mapping ──────────────────────────────────────────────────────────────

DEVICE_INTENTS = {
    "alarm", "timer", "calendar", "reminder", "reminder_update", "reminder_abort",
    "calculator", "phone_call", "redial", "smart_home", "iot_hue_lightchange",
    "iot_hue_lightoff", "iot_hue_lighton", "iot_hue_lightdim", "iot_cleaning",
    "iot_coffee", "iot_wemo_off", "iot_wemo_on", "play_music", "pausing",
    "play_podcasts", "play_audiobooks",
}
PERSONAL_INTENTS = {
    "doctor", "medical_conditions", "prescription", "insurance", "vaccines",
    "income", "taxes", "bill_balance", "pay_bill", "transfer", "credit_score",
    "bank_balance", "interest_rate", "mortgage", "report_lost_card",
    "travel_alert", "todo_list", "todo_list_update", "todo_list_item",
    "shopping_list", "shopping_list_update",
}
FACTUAL_INTENTS = {
    "weather", "temperature", "humidity", "local_weather_quality", "weather_historical",
    "geography", "distance", "time_zone", "directions", "traffic",
    "translate", "definition", "synonym", "antonym", "spelling",
    "conversion", "measurement", "date", "time", "timezone",
    "currency", "exchange_rate", "nutritional_info", "calories", "ingredient",
    "population", "language", "flight_status", "plug_type", "international_visa",
    "recipe", "what_is_this_song", "food_last", "spending_history",
}
CONVERSATIONAL_INTENTS = {
    "greeting", "goodbye", "yes", "no", "maybe", "thank_you", "sorry",
    "tell_joke", "fun_fact", "meaning_of_life", "who_made_you", "who_do_you_work_for",
    "what_can_i_ask_you", "change_speed", "change_volume", "change_language",
    "change_user_name", "repeat", "next_song", "play_radio", "cancel",
    "cancel_reservation", "reset_settings",
}

LABEL2ID = {
    "DEVICE_ACTION": 0,
    "PERSONAL_QUERY": 1,
    "FACTUAL_QUERY": 2,
    "CONVERSATIONAL": 3,
    "AMBIGUOUS": 4,
}
ID2LABEL = {v: k for k, v in LABEL2ID.items()}


def clinc_intent_to_label(intent_name: str) -> int:
    if intent_name in DEVICE_INTENTS:
        return LABEL2ID["DEVICE_ACTION"]
    if intent_name in PERSONAL_INTENTS:
        return LABEL2ID["PERSONAL_QUERY"]
    if intent_name in FACTUAL_INTENTS:
        return LABEL2ID["FACTUAL_QUERY"]
    if intent_name in CONVERSATIONAL_INTENTS:
        return LABEL2ID["CONVERSATIONAL"]
    return LABEL2ID["AMBIGUOUS"]


# ── Data loading ───────────────────────────────────────────────────────────────

def load_clinc():
    ds = load_dataset("clinc_oos", "plus")
    intent_names = ds["train"].features["intent"].names

    def remap(example):
        original_label = intent_names[example["intent"]]
        example["label"] = clinc_intent_to_label(original_label)
        return example

    return ds.map(remap, remove_columns=["intent"])


# ── Training ───────────────────────────────────────────────────────────────────

def compute_metrics(eval_pred):
    logits, labels = eval_pred
    preds = np.argmax(logits, axis=-1)
    report = classification_report(labels, preds, target_names=list(LABEL2ID.keys()), output_dict=True)
    return {
        "f1_macro": report["macro avg"]["f1-score"],
        "accuracy": report["accuracy"],
    }


def main():
    model_name = "google/mobilebert-uncased"
    output_dir = Path("outputs/mobilebert_classifier")
    output_dir.mkdir(parents=True, exist_ok=True)

    tokenizer = AutoTokenizer.from_pretrained(model_name)
    ds = load_clinc()

    def tokenize(batch):
        return tokenizer(batch["text"], truncation=True, max_length=64)

    tokenized = ds.map(tokenize, batched=True, remove_columns=["text"])

    model = AutoModelForSequenceClassification.from_pretrained(
        model_name,
        num_labels=len(LABEL2ID),
        id2label=ID2LABEL,
        label2id=LABEL2ID,
    )

    args = TrainingArguments(
        output_dir=str(output_dir),
        num_train_epochs=5,
        per_device_train_batch_size=32,
        per_device_eval_batch_size=64,
        learning_rate=3e-5,
        warmup_ratio=0.06,
        weight_decay=0.01,
        evaluation_strategy="epoch",
        save_strategy="epoch",
        load_best_model_at_end=True,
        metric_for_best_model="f1_macro",
        fp16=torch.cuda.is_available(),
        report_to="none",
        logging_steps=50,
    )

    trainer = Trainer(
        model=model,
        args=args,
        train_dataset=tokenized["train"],
        eval_dataset=tokenized["validation"],
        tokenizer=tokenizer,
        data_collator=DataCollatorWithPadding(tokenizer),
        compute_metrics=compute_metrics,
    )

    trainer.train()
    trainer.save_model(str(output_dir))
    tokenizer.save_pretrained(str(output_dir))
    print(f"Model saved to {output_dir}")

    # Final test-set evaluation
    results = trainer.evaluate(tokenized["test"])
    print("Test results:", results)


if __name__ == "__main__":
    main()
