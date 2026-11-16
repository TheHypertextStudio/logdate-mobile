#!/bin/bash

# LogDate Development Environment Logs Script
# This script shows logs from development services

set -e

# Colors for output
BLUE='\033[0;34m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

echo -e "${BLUE}📋 LogDate Development Logs${NC}"

# Default to following logs for all services
if [[ $# -eq 0 ]]; then
  echo -e "${GREEN}Following logs for all services (Ctrl+C to stop)...${NC}"
  docker-compose logs -f
else
  # Show logs for specific service
  SERVICE="$1"

  case $SERVICE in
    "postgres" | "db" | "database")
      echo -e "${GREEN}Following PostgreSQL logs...${NC}"
      docker-compose logs -f logdate-postgres
      ;;
    "server" | "app")
      echo -e "${GREEN}Following LogDate server logs...${NC}"
      docker-compose logs -f logdate-server
      ;;
    "pgadmin")
      echo -e "${GREEN}Following PgAdmin logs...${NC}"
      docker-compose logs -f logdate-pgadmin
      ;;
    *)
      echo "❌ Unknown service: $SERVICE"
      echo ""
      echo "Available services:"
      echo "  postgres, server, pgadmin"
      echo ""
      echo "Usage: $0 [service-name]"
      echo "  No arguments: Show all logs"
      exit 1
      ;;
  esac
fi
