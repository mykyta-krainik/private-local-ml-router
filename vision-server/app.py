"""
Visual PII detection server.

Serves POST /detect for the JVM demo server's JvmVisualPiiDetector.

Request:  { "image_base64": "<base64>", "mime_type": "image/jpeg" }
Response: { "detections": [{ "type": "FACE", "confidence": 0.91,
                              "x": 0.05, "y": 0.1, "w": 0.2, "h": 0.3 }] }

Model resolution order:
  1. /models/best.pt   — fine-tuned weights from `make train-visual`
  2. yolov8n.pt        — ultralytics pretrained fallback (auto-downloaded)
"""

import base64
import io
import logging
import os

from flask import Flask, jsonify, request
from PIL import Image
from ultralytics import YOLO

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("vision-server")

CLASSES = [
    "FACE", "DOCUMENT_ID", "CARD_PAYMENT", "LICENSE_PLATE",
    "SCREEN", "MEDICAL_DOC", "HANDWRITTEN_FORM",
]

FINE_TUNED_PATH = "/models/best.pt"
FALLBACK_MODEL  = "yolov8n.pt"
CONF_THRESHOLD  = float(os.getenv("CONF_THRESHOLD", "0.45"))


def load_model() -> YOLO:
    if os.path.exists(FINE_TUNED_PATH):
        log.info("Loading fine-tuned weights from %s", FINE_TUNED_PATH)
        return YOLO(FINE_TUNED_PATH)
    log.warning("Fine-tuned weights not found at %s — using pretrained %s", FINE_TUNED_PATH, FALLBACK_MODEL)
    return YOLO(FALLBACK_MODEL)


app   = Flask(__name__)
model = load_model()


@app.post("/detect")
def detect():
    data = request.get_json(force=True)
    img_bytes = base64.b64decode(data["image_base64"])
    img = Image.open(io.BytesIO(img_bytes)).convert("RGB")

    results = model(img, conf=CONF_THRESHOLD, verbose=False)[0]

    detections = []
    for box in results.boxes:
        cls = int(box.cls[0])
        if cls >= len(CLASSES):
            continue
        # xywhn: normalized cx,cy,w,h → convert to top-left x,y,w,h
        cx, cy, w, h = box.xywhn[0].tolist()
        detections.append({
            "type":       CLASSES[cls],
            "confidence": round(float(box.conf[0]), 4),
            "x":          round(cx - w / 2, 4),
            "y":          round(cy - h / 2, 4),
            "w":          round(w, 4),
            "h":          round(h, 4),
        })

    return jsonify({"detections": detections})


@app.get("/health")
def health():
    return jsonify({"status": "ok", "model": FINE_TUNED_PATH if os.path.exists(FINE_TUNED_PATH) else FALLBACK_MODEL})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
