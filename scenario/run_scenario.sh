#!/bin/sh
java -server -Xmx12289m -XX:-UseGCOverheadLimit -XX:+UseParallelGC -Done-jar.silent=true -jar target/NIBtbModelScenario.jar -c config_4kmradius_max.txt -p parameters.txt -l info $*

