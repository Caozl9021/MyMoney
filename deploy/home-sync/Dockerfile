FROM python:3.12-slim

WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=10

COPY server/pyproject.toml server/alembic.ini server/schema.sql ./
COPY server/constraints.txt ./constraints.txt
COPY server/app ./app
COPY server/migrations ./migrations
RUN pip install --no-cache-dir --timeout 120 --retries 10 -c constraints.txt .
RUN mkdir -p /data/backups
EXPOSE 8080
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${MYMONEY_PORT:-8080}"]
