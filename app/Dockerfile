FROM python:3.11-slim

# python:3.11-slim strips tzdata by default (Debian slim images drop it to
# save space), so TZ=Europe/Moscow from docker-compose.yml has nothing to
# apply to — Python's local-time functions have no zoneinfo files to read
# the +3 offset from. Install tzdata explicitly so TZ actually works.
RUN apt-get update && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Europe/Moscow

WORKDIR /app
ENV PYTHONPATH=/app

COPY app/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
