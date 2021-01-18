#!/bin/sh
java -server -Xmx24576m -XX:-UseGCOverheadLimit -XX:+UseParallelGC -Done-jar.silent=true -jar target/NIBtbClusterModelController-0.0-jar-with-dependencies.jar $*

#java -server -Xmx24576m -XX:-UseGCOverheadLimit -XX:+UseParallelGC -XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:+HeapDumpOnOutOfMemoryError  -Done-jar.silent=true -jar target/NIBtbClusterModelController-0.0-jar-with-dependencies.jar $*


#mvn exec:java -Dexec.mainClass=btbcluster.NIBtbClusterController -Dexec.args="-c config.txt -l info"
