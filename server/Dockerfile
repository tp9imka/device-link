FROM python:3.14.6-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 RELAY_DATABASE=/data/relay.sqlite3
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home relay && mkdir /data && chown relay:relay /data
COPY relay/ ./relay/
USER relay
VOLUME /data
EXPOSE 8000
CMD ["python", "-m", "relay"]
