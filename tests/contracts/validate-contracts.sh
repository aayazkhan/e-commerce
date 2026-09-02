#!/usr/bin/env bash
set -euo pipefail

command -v ruby >/dev/null || { echo "ruby is required for contract validation" >&2; exit 1; }
ruby -ryaml <<'RUBY'
Dir['backend/*/openapi.yaml'].sort.each do |path|
  document = YAML.load_file(path)
  abort "#{path}: missing OpenAPI version" unless document.is_a?(Hash) && document['openapi']
  abort "#{path}: missing paths" unless document['paths'].is_a?(Hash)
  abort "#{path}: no paths declared" if document['paths'].empty?
end
puts "OpenAPI contracts parsed: #{Dir['backend/*/openapi.yaml'].length}"
RUBY

while IFS='|' read -r service dockerfile; do
  [[ -z "$service" || "$service" == \#* ]] && continue
  [[ -f "$dockerfile" ]] || { echo "missing Dockerfile for $service: $dockerfile" >&2; exit 1; }
done < ops/release/services.txt
echo "release service/Dockerfile map validated"
