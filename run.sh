#!/bin/sh
cd $(dirname $0)


rm -rf build
./gradlew build
ret=$?
if [ $ret -ne 0 ]; then
exit $ret
fi
java -jar build/libs/uniswap-v3-backtest-0.0.1.jar

exit
