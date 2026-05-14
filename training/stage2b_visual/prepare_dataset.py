"""
Prepare merged YOLO-format dataset for visual PII detection.

Sources:
  1. Roboflow Universe — license plate datasets (public, free tier)
  2. MIDV-500 — identity document images
  3. Open Images v7 — face, credit card subsets via FiftyOne

Output: datasets/pii_visual_merged/  (YOLO format with data.yaml)

Class mapping (must match YoloPiiDetector.CLASS_TYPES):
  0: FACE
  1: DOCUMENT_ID
  2: CARD_PAYMENT
  3: LICENSE_PLATE
  4: SCREEN
  5: MEDICAL_DOC
  6: HANDWRITTEN_FORM
"""

import json
import os
import shutil
from pathlib import Path

import yaml

DATASET_DIR = Path("datasets/pii_visual_merged")
DATASET_DIR.mkdir(parents=True, exist_ok=True)

CLASSES = ["FACE", "DOCUMENT_ID", "CARD_PAYMENT", "LICENSE_PLATE", "SCREEN", "MEDICAL_DOC", "HANDWRITTEN_FORM"]
CLASS_TO_ID = {c: i for i, c in enumerate(CLASSES)}

for split in ("train", "val", "test"):
    (DATASET_DIR / split / "images").mkdir(parents=True, exist_ok=True)
    (DATASET_DIR / split / "labels").mkdir(parents=True, exist_ok=True)


def download_roboflow_license_plates():
    """Download Roboflow license plate dataset (requires ROBOFLOW_API_KEY env var)."""
    api_key = os.getenv("ROBOFLOW_API_KEY")
    if not api_key:
        print("ROBOFLOW_API_KEY not set — skipping Roboflow download")
        return

    try:
        from roboflow import Roboflow
        rf = Roboflow(api_key=api_key)
        project = rf.workspace("roboflow-universe-projects").project("license-plate-recognition-rxg4e")
        dataset = project.version(4).download("yolov8", location=str(Path("datasets/roboflow_lp")))
        _remap_roboflow(Path("datasets/roboflow_lp"), src_class_id=0, dst_class_id=CLASS_TO_ID["LICENSE_PLATE"])
    except Exception as e:
        print(f"Roboflow download failed: {e}")


def _remap_roboflow(src_dir: Path, src_class_id: int, dst_class_id: int):
    for split in ("train", "valid", "test"):
        label_dir = src_dir / split / "labels"
        img_dir = src_dir / split / "images"
        dst_split = "val" if split == "valid" else split
        if not label_dir.exists():
            continue
        for lbl_file in label_dir.glob("*.txt"):
            lines = []
            for line in lbl_file.read_text().splitlines():
                parts = line.split()
                if not parts:
                    continue
                cls = int(parts[0])
                if cls == src_class_id:
                    parts[0] = str(dst_class_id)
                lines.append(" ".join(parts))
            (DATASET_DIR / dst_split / "labels" / lbl_file.name).write_text("\n".join(lines))
            for ext in (".jpg", ".jpeg", ".png"):
                img = img_dir / (lbl_file.stem + ext)
                if img.exists():
                    shutil.copy(img, DATASET_DIR / dst_split / "images" / img.name)
                    break


def download_open_images_faces():
    """Download face images from Open Images v7 via FiftyOne."""
    try:
        import fiftyone as fo
        import fiftyone.zoo as foz

        dataset = foz.load_zoo_dataset(
            "open-images-v7",
            split="train",
            label_types=["detections"],
            classes=["Human face"],
            max_samples=5000,
        )
        _export_fiftyone_to_yolo(dataset, dst_class_id=CLASS_TO_ID["FACE"], src_label="Human face")
        fo.delete_dataset(dataset.name)
    except Exception as e:
        print(f"Open Images faces download failed (FiftyOne required): {e}")


def _export_fiftyone_to_yolo(dataset, dst_class_id: int, src_label: str):
    import fiftyone as fo

    splits = {"train": 0.8, "val": 0.1, "test": 0.1}
    samples = list(dataset)
    n = len(samples)
    boundaries = [int(n * 0.8), int(n * 0.9)]

    for i, sample in enumerate(samples):
        split = "train" if i < boundaries[0] else ("val" if i < boundaries[1] else "test")
        img_path = Path(sample.filepath)
        shutil.copy(img_path, DATASET_DIR / split / "images" / img_path.name)

        detections = sample.ground_truth.detections if sample.ground_truth else []
        lines = []
        for det in detections:
            if det.label != src_label:
                continue
            x, y, w, h = det.bounding_box  # [0,1] relative
            cx, cy = x + w / 2, y + h / 2
            lines.append(f"{dst_class_id} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")

        lbl_path = DATASET_DIR / split / "labels" / (img_path.stem + ".txt")
        lbl_path.write_text("\n".join(lines))


def generate_synthetic_cards(n: int = 1000):
    """Generate synthetic credit card images using Faker (no real data)."""
    try:
        from faker import Faker
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print("faker / Pillow not installed — skipping synthetic card generation")
        return

    fake = Faker()
    for i in range(n):
        img = Image.new("RGB", (856, 540), color=(
            fake.random_int(0, 80), fake.random_int(0, 80), fake.random_int(80, 180)
        ))
        draw = ImageDraw.Draw(img)
        card_num = fake.credit_card_number(card_type=None)
        draw.text((60, 280), card_num, fill=(220, 220, 220))
        draw.text((60, 360), fake.name(), fill=(200, 200, 200))
        draw.text((60, 400), fake.credit_card_expire(), fill=(200, 200, 200))

        split = "train" if i < n * 0.8 else ("val" if i < n * 0.9 else "test")
        fname = f"synth_card_{i:05d}.jpg"
        img.save(DATASET_DIR / split / "images" / fname)
        # Full image is the card
        (DATASET_DIR / split / "labels" / f"synth_card_{i:05d}.txt").write_text(
            f"{CLASS_TO_ID['CARD_PAYMENT']} 0.500000 0.500000 1.000000 1.000000"
        )


def write_data_yaml():
    yaml_content = {
        "path": str(DATASET_DIR.resolve()),
        "train": "train/images",
        "val": "val/images",
        "test": "test/images",
        "nc": len(CLASSES),
        "names": CLASSES,
    }
    (DATASET_DIR / "data.yaml").write_text(yaml.dump(yaml_content, sort_keys=False))
    print(f"data.yaml written to {DATASET_DIR}/data.yaml")


if __name__ == "__main__":
    print("Preparing visual PII dataset...")
    download_roboflow_license_plates()
    download_open_images_faces()
    generate_synthetic_cards(n=1000)
    write_data_yaml()

    for split in ("train", "val", "test"):
        count = len(list((DATASET_DIR / split / "images").glob("*")))
        print(f"  {split}: {count} images")
