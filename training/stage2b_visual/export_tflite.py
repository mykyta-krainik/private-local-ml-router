"""
Export the fine-tuned YOLOv8-nano PII detector to INT8 TFLite.

Input:  runs/detect/yolov8n_pii/weights/best.pt
Output: tflite_exports/yolov8n_pii.tflite

The exported model expects:
  Input:  [1, 640, 640, 3] FLOAT32, normalised 0..1
  Output: [1, 7, 8400] FLOAT32 (transposed YOLO format: cx,cy,w,h,cls0..cls6)

Copy to Android assets:
  cp tflite_exports/yolov8n_pii.tflite ../privacy-router/src/main/assets/
"""

import os
import sys
from pathlib import Path
import shutil

# Allow overriding the weights path via env var or CLI arg
_default = Path("runs/detect/yolov8n_pii/weights/best.pt")
WEIGHTS = Path(os.getenv("YOLO_WEIGHTS", sys.argv[1] if len(sys.argv) > 1 else str(_default)))
EXPORT_DIR = Path("tflite_exports")
EXPORT_DIR.mkdir(parents=True, exist_ok=True)


def main():
    if not WEIGHTS.exists():
        print(f"Weights not found at {WEIGHTS} — run train_yolov8n.py first")
        print(f"Tip: pass the path as an argument:  python export_tflite.py /path/to/best.pt")
        return

    from ultralytics import YOLO

    model = YOLO(str(WEIGHTS))

    # Export to INT8 TFLite (ultralytics handles the full pipeline)
    exported = model.export(
        format="tflite",
        imgsz=640,
        int8=True,
        data="datasets/pii_visual_merged/data.yaml",  # calibration dataset
    )

    # Ultralytics saves the .tflite next to the .pt file; copy to tflite_exports/
    exported_path = Path(str(exported))
    if not exported_path.exists():
        # Try common naming pattern
        exported_path = WEIGHTS.parent / "best_integer_quant.tflite"
        if not exported_path.exists():
            exported_path = WEIGHTS.with_suffix("_integer_quant.tflite")

    if exported_path.exists():
        dst = EXPORT_DIR / "yolov8n_pii.tflite"
        shutil.copy(exported_path, dst)
        size_kb = dst.stat().st_size / 1024
        print(f"INT8 YOLO TFLite saved: {dst} ({size_kb:.1f} KB)")
        print(f"\nCopy to Android assets:")
        print(f"  cp {dst} ../privacy-router/src/main/assets/")
    else:
        print(f"Export produced: {exported}")
        print("Manually copy the .tflite file to tflite_exports/yolov8n_pii.tflite")


if __name__ == "__main__":
    main()
