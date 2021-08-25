.phony: ci, ephemeral, persistent, bench, clean, run-jar-h2, run-jar-sqlite, bundle, run-jar-h2-persistent, run-jar-postgres

ci:
	clojure -X:test

ephemeral:
	LRSQL_DB_NAME=ephemeral \
		LRSQL_API_KEY_DEFAULT=username \
		LRSQL_API_SECRET_DEFAULT=password \
		clojure -M:db-h2 -m lrsql.h2.main --persistent false

persistent:
	LRSQL_DB_NAME=persistent \
		LRSQL_API_KEY_DEFAULT=username \
		LRSQL_API_SECRET_DEFAULT=password \
		clojure -M:db-h2 -m lrsql.h2.main --persistent true

sqlite:
	LRSQL_DB_NAME=lrsql.sqlite.db \
		LRSQL_API_KEY_DEFAULT=username \
		LRSQL_API_SECRET_DEFAULT=password \
		clojure -M:db-sqlite -m lrsql.sqlite.main

# NOTE: Requires a running PG instance!
postgres:
	LRSQL_DB_NAME=lrsql_pg \
		LRSQL_DB_USERNAME=lrsql_user \
		LRSQL_DB_PASSWORD=swordfish \
		LRSQL_DB_PROPERTIES=currentSchema:lrsql \
		LRSQL_API_KEY_DEFAULT=username \
		LRSQL_API_SECRET_DEFAULT=password \
		clojure -M:db-postgres -m lrsql.postgres.main

clean-dev:
	rm -f *.db *.log

# Intended for use with `make ephemeral` or `make persistent`
bench:
	clojure -M:bench -m lrsql.bench http://localhost:8080/xapi/statements \
		-i dev-resources/default/insert_input.json \
		-q dev-resources/default/query_input.json \
		-u username -p password

# Build
clean:
	rm -rf target

# Compile and make Uberjar
target/bundle/lrsql.jar:
	clojure -Xbuild uber

# Copy scripts
target/bundle/bin:
	mkdir -p target/bundle
	cp -r bin target/bundle/bin
	chmod +x target/bundle/bin/*.sh

# Create HTML docs
# TODO: make this a .zip file
target/bundle/doc:
	clojure -M:doc -m lrsql.render-doc doc target/bundle/doc

# Copy config
target/bundle/config/lrsql.json.example:
	mkdir -p target/bundle/config
	cp resources/lrsql/config/lrsql.json.example target/bundle/config/lrsql.json.example

target/bundle/config/authority.json.template.example:
	mkdir -p target/bundle/config
	cp resources/lrsql/config/authority.json.template target/bundle/config/authority.json.template.example

target/bundle/config: target/bundle/config/lrsql.json.example target/bundle/config/authority.json.template.example

# entire bundle
target/bundle: target/bundle/config target/bundle/doc target/bundle/lrsql.jar target/bundle/bin

bundle: target/bundle

# dev build testing stuff
run-jar-h2: target/bundle
	cd target/bundle; LRSQL_API_KEY_DEFAULT=username LRSQL_API_SECRET_DEFAULT=password bin/run_h2.sh

run-jar-h2-persistent: target/bundle
	cd target/bundle; LRSQL_API_KEY_DEFAULT=username LRSQL_API_SECRET_DEFAULT=password bin/run_h2_persistent.sh

run-jar-sqlite: target/bundle
	cd target/bundle; LRSQL_API_KEY_DEFAULT=username LRSQL_API_SECRET_DEFAULT=password bin/run_sqlite.sh

run-jar-postgres: target/bundle
	cd target/bundle; LRSQL_API_KEY_DEFAULT=username LRSQL_API_SECRET_DEFAULT=password bin/run_postgres.sh
