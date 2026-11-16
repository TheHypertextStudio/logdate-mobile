#!/bin/bash

# LogDate Development Environment Status Script
# This script shows the status of all development services

set -e

# Colors for output
BLUE='\033[0;34m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}📊 LogDate Development Environment Status${NC}"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
  echo -e "${RED}❌ Docker is not running${NC}"
  exit 1
fi

# Show service status
echo -e "${GREEN}🐳 Docker Services:${NC}"
docker-compose ps

echo ""

# Check service health
echo -e "${GREEN}🏥 Service Health:${NC}"

# PostgreSQL
if docker-compose ps logdate-postgres | grep -q "Up.*healthy"; then
  echo -e "  PostgreSQL: ${GREEN}✅ Healthy${NC} (localhost:15432)"
elif docker-compose ps logdate-postgres | grep -q "Up"; then
  echo -e "  PostgreSQL: ${YELLOW}⏳ Starting${NC} (localhost:15432)"
else
  echo -e "  PostgreSQL: ${RED}❌ Down${NC}"
fi

# Development Tools
echo ""
echo -e "${GREEN}🛠️  Development Tools:${NC}"

if docker-compose ps logdate-pgadmin | grep -q "Up"; then
  echo -e "  PgAdmin:        ${GREEN}✅ Running${NC} (http://localhost:15050)"
else
  echo -e "  PgAdmin:        ${RED}❌ Down${NC}"
fi

# Show volumes
echo ""
echo -e "${GREEN}💾 Data Volumes:${NC}"
docker volume ls | grep "logdate" | while read -r line; do
  echo "  $line"
done

echo ""
echo -e "${BLUE}💡 Quick Commands:${NC}"
echo "  Start:   ./scripts/dev-start.sh"
echo "  Stop:    ./scripts/dev-stop.sh"
echo "  Reset:   ./scripts/dev-reset.sh"
echo "  Logs:    ./scripts/dev-logs.sh"
