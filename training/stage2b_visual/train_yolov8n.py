"""
Stage 2B visual PII detector: fine-tune YOLOv8-nano on the merged dataset.

Input:  datasets/pii_visual_merged/data.yaml
Output: runs/detect/yolov8n_pii/weights/best.pt

Class order (must match YoloPiiDetector.CLASS_TYPES in Android):
  0: FACE, 1: DOCUMENT_ID, 2: CARD_PAYMENT, 3: LICENSE_PLATE,
  4: SCREEN, 5: MEDICAL_DOC, 6: HANDWRITTEN_FORM
"""

import os
from pathlib import Path

DATA_YAML = Path("datasets/pii_visual_merged/data.yaml")
EPOCHS = int(os.getenv("EPOCHS", "100"))
IMGSZ = int(os.getenv("IMGSZ", "640"))
BATCH = int(os.getenv("BATCH", "16"))
DEVICE = os.getenv("DEVICE", "0" if __import__("torch").cuda.is_available() else "cpu")


def main():
    from ultralytics import YOLO

    if not DATA_YAML.exists():
        print(f"Dataset not found at {DATA_YAML} — run prepare_dataset.py first")
        return

    model = YOLO("yolov8n.pt")

    results = model.train(
        data=str(DATA_YAML),
        epochs=EPOCHS,
        imgsz=IMGSZ,
        batch=BATCH,
        device=DEVICE,
        project="runs/detect",
        name="yolov8n_pii",
        exist_ok=True,
        # Augmentation — help generalise across lighting/angle variation
        hsv_h=0.015,
        hsv_s=0.7,
        hsv_v=0.4,
        degrees=10.0,
        translate=0.1,
        scale=0.5,
        flipud=0.0,
        fliplr=0.5,
        mosaic=1.0,
        mixup=0.1,
        # Regularisation
        weight_decay=0.0005,
        warmup_epochs=3,
        # Metrics
        val=True,
        plots=True,
        save=True,
    )

    best_weights = Path("runs/detect/yolov8n_pii/weights/best.pt")
    print(f"\nTraining complete. Best weights: {best_weights}")
    print(f"mAP50: {results.results_dict.get('metrics/mAP50(B)', 'n/a')}")
    print(f"mAP50-95: {results.results_dict.get('metrics/mAP50-95(B)', 'n/a')}")
    print("\nNext step: run export_tflite.py")


if __name__ == "__main__":
    main()
