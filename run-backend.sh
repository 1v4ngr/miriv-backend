#!/usr/bin/env bash
# run-backend.sh — arranca el backend MIRIV (Spring Boot) en macOS / Linux.
#
# Hace, en orden:
#   1. Cd al directorio del script (para que funcione desde cualquier ruta).
#   2. Resuelve JAVA_HOME (env > Homebrew openjdk) y lo exporta.
#   3. Si SKIP_DOCKER no está definido y docker compose está disponible,
#      levanta Postgres con `docker compose up -d` (idempotente).
#   4. Espera hasta 30s a que el puerto de Postgres responda.
#   5. Lanza `mvn spring-boot:run` con el perfil dev (mismas vars que el .cmd).
#
# Variables de entorno reconocidas:
#   JAVA_HOME    - si está, se usa tal cual.
#   SERVER_PORT  - puerto HTTP (default 8080, el application.yml).
#   DB_URL / DB_USERNAME / DB_PASSWORD - overridean la conexión dev.
#   SKIP_DOCKER  - si vale 1, no toca docker compose.
#   NO_SEED      - si vale 1, no carga db/dev-seed (usar con una copia de prod).
#
# Argumentos:
#   --no-seed    - equivalente a NO_SEED=1.

set -euo pipefail

for arg in "$@"; do
  case "$arg" in
    --no-seed) NO_SEED=1 ;;
    *) echo "ERROR: argumento desconocido: $arg" >&2; exit 1 ;;
  esac
done

# 1) Directorio del script.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> MIRIV backend runner"
echo "    dir: $SCRIPT_DIR"

# 2) Resolver JAVA_HOME.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
      /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
      /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
      /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home; do
    if [[ -d "$candidate" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "ERROR: no se ha encontrado un JDK. Define JAVA_HOME o instala openjdk con Homebrew." >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo "    java: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "    JAVA_HOME: $JAVA_HOME"

# 3) Comprobar Maven.
if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn no está en PATH. Instálalo con: brew install maven" >&2
  exit 1
fi
echo "    mvn:   $(mvn -v 2>/dev/null | head -1)"

# 4) Levantar Postgres con docker compose si procede.
if [[ "${SKIP_DOCKER:-0}" != "1" ]]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: docker no está instalado. Instala Docker Desktop o define SKIP_DOCKER=1 para usar Postgres externo." >&2
    exit 1
  fi

  # Si el daemon no responde, intentar arrancar Docker Desktop.
  if ! docker info >/dev/null 2>&1; then
    if [[ "$(uname -s)" == "Darwin" ]] && [[ -d "/Applications/Docker.app" ]]; then
      echo "==> Docker daemon apagado. Arrancando Docker Desktop…"
      open -a Docker
      echo "==> Esperando al daemon de Docker (máx 60s)…"
      for i in {1..60}; do
        if docker info >/dev/null 2>&1; then
          echo "    Docker daemon listo."
          break
        fi
        sleep 1
      done
    fi

    if ! docker info >/dev/null 2>&1; then
      echo "ERROR: Docker no responde. Abre Docker Desktop y reintenta, o usa SKIP_DOCKER=1." >&2
      exit 1
    fi
  fi

  if [[ ! -f docker-compose.yml ]]; then
    echo "AVISO: no hay docker-compose.yml en $SCRIPT_DIR; asumo Postgres externo."
  else
    echo "==> docker compose up -d (Postgres)"
    docker compose up -d

    # 5) Esperar a que el contenedor de Postgres esté healthy (o al menos en running).
    CONTAINER_NAME="miriv-postgres"
    echo "==> Esperando a que ${CONTAINER_NAME} esté healthy…"
    PG_OK=0
    for i in {1..60}; do
      STATUS="$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER_NAME" 2>/dev/null || true)"
      if [[ "$STATUS" == "healthy" ]]; then
        echo "    Postgres healthy."
        PG_OK=1
        break
      fi
      # Fallback: si no hay healthcheck configurado, comprobar running.
      RUNNING="$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || true)"
      if [[ "$RUNNING" == "true" && -z "$STATUS" ]]; then
        echo "    Postgres en ejecución (sin healthcheck)."
        PG_OK=1
        break
      fi
      sleep 1
    done

    if [[ "$PG_OK" -ne 1 ]]; then
      echo "ERROR: Postgres no se ha puesto en marcha a tiempo. Mira 'docker compose logs postgres'." >&2
      exit 1
    fi
  fi
fi

# 6) Lanzar Spring Boot.
PORT="${SERVER_PORT:-8080}"
echo "==> mvn spring-boot:run (SERVER_PORT=$PORT)"
echo "    health: http://localhost:${PORT}/actuator/health"
echo "    docs:   http://localhost:${PORT}/docs"
if [[ "${NO_SEED:-0}" == "1" ]]; then
  # Copia de prod: solo migraciones versionadas, sin usuarios de desarrollo.
  export SPRING_FLYWAY_LOCATIONS="classpath:db/migration"
  echo "    seed:   desactivado (NO_SEED=1)"
fi
echo

exec mvn spring-boot:run
