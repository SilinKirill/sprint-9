FROM python:3.12-alpine
RUN apk add --no-cache openssl
COPY prepare.py /prepare.py
ENTRYPOINT ["python", "/prepare.py"]
