.PHONY: help dev dev-down build test clean web-install dev-server dev-web build-web test-web typecheck-web lint-web generate-api prod-up prod-down logs db-reset

help: ## 显示帮助
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

dev: ## 启动本地开发环境（仅依赖服务）
	docker compose up -d
	@echo "Waiting for services to be healthy..."
	@sleep 5
	@echo "Services ready. Start backend with: make dev-server"
	@echo "Start frontend with: make dev-web"

dev-server: ## 启动后端开发服务器
	cd server && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

dev-down: ## 停止本地开发环境
	docker compose down

build: ## 构建后端
	cd server && ./mvnw clean package -DskipTests

test: ## 运行后端测试
	cd server && ./mvnw test

clean: ## 清理构建产物
	cd server && ./mvnw clean
	docker compose down -v

generate-api: ## 生成 OpenAPI 类型（前端用）
	@echo "Generating OpenAPI types..."
	cd web && pnpm run generate-api

web-install: ## 安装前端依赖
	cd web && pnpm install

dev-web: ## 启动前端开发服务器
	cd web && pnpm run dev

build-web: ## 构建前端
	cd web && pnpm run build

test-web: ## 运行前端测试
	cd web && pnpm run test

typecheck-web: ## 前端类型检查
	cd web && pnpm run typecheck

lint-web: ## 前端代码检查
	cd web && pnpm run lint

prod-up: ## 一键启动全部服务（生产模式）
	docker compose -f docker-compose.prod.yml up -d --build

prod-down: ## 停止全部服务（生产模式）
	docker compose -f docker-compose.prod.yml down

logs: ## 查看后端服务日志
	docker compose -f docker-compose.prod.yml logs -f server

db-reset: ## 重置数据库
	docker compose down -v
	docker compose up -d postgres
	@echo "Waiting for postgres..."
	@sleep 3
	cd server && ./mvnw flyway:migrate -pl skillhub-app
