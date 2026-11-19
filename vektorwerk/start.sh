#!/usr/bin/env bash
# Startet Vektorwerk und öffnet es im Browser.
cd "$(dirname "$0")"
( sleep 2; xdg-open http://localhost:8765 >/dev/null 2>&1 ) &
exec uv run uvicorn app.main:app --port 8765
