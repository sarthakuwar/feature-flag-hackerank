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

# Local-dev convenience only: when MONGODB_URI points at another host there is nothing to start here.
uses_remote_mongo() {
    case "${MONGODB_URI:-}" in
        ""|*localhost*|*127.0.0.1*|*"[::1]"*) return 1 ;;
        *) return 0 ;;
    esac
}

check_mongo() {
    if uses_remote_mongo; then
        log_info "MONGODB_URI targets another host; skipping the local MongoDB check"
        return
    fi
    if mongo_is_reachable; then
        log_info "MongoDB is already reachable on ${MONGO_PORT}"
        return
    fi
    log_warn "MongoDB is not reachable; starting a local one (development convenience only)"
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

# Gradle skips up-to-date work, so this is quick when nothing changed and correct when sources did.
setup_backend() {
    log_info "Building the Feature Flag backend"
    ./backend/gradlew -p backend classes --quiet --console=plain
}

# There is no Gradle seed task: FlagSeeder resets and reseeds the database when the backend starts.
seed_database() {
    log_info "Seed data is loaded when the backend starts (FlagSeeder resets a local MongoDB; FEATUREFLAG_SEED=true forces a remote one)"
}

setup_env
check_mongo
setup_backend

case "$MODE" in
    --start|--seed|"") seed_database ;;
    *) log_error "Unknown setup mode: $MODE"; exit 1 ;;
esac

log_info "Feature Flag setup complete"
