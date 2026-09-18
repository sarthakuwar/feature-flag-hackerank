#!/bin/bash
set -e

MODE="${1:-}"

log_info() { echo -e "\033[0;32m[INFO]\033[0m $1"; }
log_warn() { echo -e "\033[1;33m[WARN]\033[0m $1"; }
log_error() { echo -e "\033[0;31m[ERROR]\033[0m $1"; }

setup_env() {
    for file in .env frontend/.env backend/.env; do
        example="${file}.example"
        if [ -f "$example" ] && [ ! -f "$file" ]; then
            cp "$example" "$file"
            log_info "Created $file"
        fi
    done
}

MONGO_HOST="127.0.0.1"
MONGO_PORT="27017"

mongo_is_reachable() {
    (exec 3<>"/dev/tcp/${MONGO_HOST}/${MONGO_PORT}") >/dev/null 2>&1
}

check_mongo() {
    if mongo_is_reachable; then
        log_info "MongoDB is already reachable on ${MONGO_PORT}"
        return
    fi
    log_warn "MongoDB is not reachable; starting it"
    if [ -f /etc/mongod.conf ]; then
        mongod --config /etc/mongod.conf --fork >/dev/null 2>&1 || true
    else
        mkdir -p .mongodb
        mongod --dbpath .mongodb --bind_ip "$MONGO_HOST" --port "$MONGO_PORT" --fork --logpath .mongodb/mongod.log >/dev/null 2>&1 || true
    fi
    mongo_is_reachable || {
        log_error "MongoDB is required on ${MONGO_HOST}:${MONGO_PORT}. Start it, then run this again."
        exit 1
    }
}

setup_backend() {
    if [ -d "backend/build/classes" ]; then
        log_info "Backend build is current"
        return
    fi
    log_info "Building the Feature Flag backend"
    ./backend/gradlew -p backend classes --quiet --console=plain
}

seed_database() {
    if ! ./backend/gradlew -p backend tasks --all --quiet --console=plain | grep -q '^seed'; then
        log_warn "No seed task defined yet; skipping database seeding"
        return
    fi
    log_info "Seeding Feature Flag database"
    ./backend/gradlew -p backend seed --quiet --console=plain
}

setup_env
check_mongo
setup_backend

case "$MODE" in
    --start|--seed|"") seed_database ;;
    *) log_error "Unknown setup mode: $MODE"; exit 1 ;;
esac

log_info "Feature Flag setup complete"
