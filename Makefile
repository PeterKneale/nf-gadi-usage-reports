# Build the plugin
assemble:
	./gradlew assemble

clean:
	rm -rf .nextflow*
	rm -rf work
	rm -rf build
	./gradlew clean

# Run the unit tests, then check the built command line tool against the golden reports
test:
	./gradlew check

# Install the plugin into local nextflow plugins dir
install:
	./gradlew install

# Publish the plugin
release:
	./gradlew releasePlugin

# Build the command line tool: build/libs/gadi-usage-report.jar
cli:
	./gradlew cliJar
