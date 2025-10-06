#!/bin/bash
cd /home/kavia/workspace/code-generation/offline-video-audio-transcriber-3279-3288/android_frontend
./gradlew lint
LINT_EXIT_CODE=$?
if [ $LINT_EXIT_CODE -ne 0 ]; then
   exit 1
fi

