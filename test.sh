#!/bin/bash
# (always ratchet threshold upwards)
lein cloverage --fail-threshold 76 --html
