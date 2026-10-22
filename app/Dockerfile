FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .
COPY static ./static

ENV AVR_HOST=10.10.4.7
ENV AVR_PORT=23

EXPOSE 7777

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7777"]
