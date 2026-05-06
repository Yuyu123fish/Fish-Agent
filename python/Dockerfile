# Fish-Agent Python Worker — PDF OCR ingest via PyMuPDF + Tesseract → Elasticsearch
FROM python:3.11-slim-bookworm

WORKDIR /app

# Tesseract OCR 引擎 + 简体中文语言包（chi_sim）
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY fish_worker ./fish_worker

ENV PYTHONUNBUFFERED=1

EXPOSE 8091

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8091/health')" || exit 1

CMD ["python", "-m", "fish_worker"]
