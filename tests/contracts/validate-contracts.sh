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

# Cross-check every OpenAPI path against the gateway's actual route table (ServiceRoutes.kt),
# using the SAME longest-prefix-wins matching rule the gateway itself implements. Catches drift
# between what a service's contract documents and what the gateway actually forwards -- e.g. a
# path that no gateway prefix covers (unreachable from outside the cluster) or one that resolves
# to a different service than the one that declared it (a routing collision, the exact class of
# bug the admin-namespace overlap turned out to be during Stage 1).
ruby -ryaml <<'RUBY'
routes_source = File.read('backend/api-gateway/src/main/kotlin/com/ecommerce/gateway/ServiceRoutes.kt')
routes = routes_source.scan(/ServiceRoute\("([^"]+)",\s*serviceBaseUrl\("([^"]+)"/)
                       .map { |prefix, service| [prefix, service] }
abort 'no routes parsed from ServiceRoutes.kt -- check the regex still matches its format' if routes.empty?

def longest_match(path, routes)
  routes.select { |prefix, _| path == prefix || path.start_with?("#{prefix}/") }
        .max_by { |prefix, _| prefix.length }
end

failures = []
Dir['backend/*/openapi.yaml'].sort.each do |file|
  owning_service = File.basename(File.dirname(file))
  document = YAML.load_file(file)
  base = document.dig('servers', 0, 'url')
  prefix = base == '/api/v1' ? '/api/v1' : ''
  document['paths'].each_key do |raw_path|
    path = raw_path.start_with?('/api/v1') ? raw_path : "#{prefix}#{raw_path}"
    next if path.start_with?('/api/v1/internal') || path.start_with?('/health') || path == '/metrics'

    match = longest_match(path, routes)
    if match.nil?
      failures << "#{owning_service} #{path}: no gateway route covers this path"
    elsif match[1] != owning_service
      failures << "#{owning_service} #{path}: gateway routes this to #{match[1]} (prefix #{match[0]}), not #{owning_service}"
    end
  end
end

unless failures.empty?
  warn 'Gateway route / OpenAPI contract mismatches:'
  failures.each { |f| warn "  - #{f}" }
  exit 1
end
puts "Gateway route table matches OpenAPI contracts for #{Dir['backend/*/openapi.yaml'].length} services"
RUBY

while IFS='|' read -r service dockerfile; do
  [[ -z "$service" || "$service" == \#* ]] && continue
  [[ -f "$dockerfile" ]] || { echo "missing Dockerfile for $service: $dockerfile" >&2; exit 1; }
done < ops/release/services.txt
echo "release service/Dockerfile map validated"
