#!/bin/bash

set -euo pipefail
./$1 --config $2 \
  --test-files 'plugins/ai-review-agent-provider/web/_bazel_ts_out_tests/*_test.js' \
  --ts-config="plugins/ai-review-agent-provider/web/tsconfig.json"
