.PHONY: dev dev-down build test clean

# 启动本地开发环境（仅依赖服务）
dev:
	docker compose up -d
	@echo "Waiting for services to be healthy..."
	@sleep 5
	@echo "Services ready. Start backend with: cd server && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"

# 停止本地开发环境
dev-down:
	docker compose down

# 构建后端
build:
	cd server && ./mvnw clean package -DskipTests

# 运行测试
test:
	cd server && ./mvnw test

# 清理构建产物
clean:
	cd server && ./mvnw clean
	docker compose down -v

# 生成 OpenAPI 类型（前端用）
generate-api:
	@echo "Generating OpenAPI types..."
	cd web && pnpm run generate-api

# 前端开发服务器
dev-web:
	cd web && pnpm run dev

# 构建前端
build-web:
	cd web && pnpm run build

# 前端测试
test-web:
	cd web && pnpm run test

# 前端类型检查
typecheck-web:
	cd web && pnpm run typecheck

# 前端代码检查
lint-web:
	cd web && pnpm run lint
