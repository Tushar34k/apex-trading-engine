# TradeEngine — Automated Trading Platform

Professional automated cryptocurrency trading platform with real-time market data, strategy execution, and risk management.

## Tech Stack

- **Frontend:** React, TypeScript, Vite, Tailwind CSS, shadcn/ui
- **Backend:** Spring Boot, PostgreSQL, Flyway
- **Exchange:** Binance (Testnet & Live)

## Getting Started

### Prerequisites

- Node.js 20+
- Docker & Docker Compose

### Development

```sh
# Install frontend dependencies
npm install

# Start the development server
npm run dev
```

### Full Stack (Docker)

```sh
docker-compose up --build
```

This starts PostgreSQL, the Spring Boot backend, and the frontend.

## Environment Variables

See `.env.example` for required configuration.

## Architecture

See `docs/BACKEND_ARCHITECTURE.md` for backend design details.
