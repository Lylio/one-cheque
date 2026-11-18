FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY src/ ./src/
COPY public/ ./public/
CMD ["sh", "-c", "ONE_CHEQUE_PORT=${PORT:-8080} exec java src/OneCheque.java"]