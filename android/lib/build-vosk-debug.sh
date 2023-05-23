#!/bin/sh

export arch=arm64-v8a

rm ./Vosk_output.log
./build-vosk.sh | tee Vosk_output.log

