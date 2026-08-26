.PHONY: up down test verify run clean

up:            ## start Postgres and Redis
	docker compose up -d --wait

down:
	docker compose down -v

test:          ## unit tests only (no Docker required)
	mvn -B test

verify:        ## unit + integration tests (requires Docker)
	mvn -B verify

run: up        ## start dependencies, then the service
	mvn -B spring-boot:run

clean:
	mvn -B clean
