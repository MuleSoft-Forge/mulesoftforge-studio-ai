Mule rules for changes:
- Change existing flows and global elements in place and reuse existing configs through config-ref
  rather than adding duplicates. Every namespace you use needs its xsi:schemaLocation entry and, for
  connectors and modules, a pom.xml dependency. Give new components a short doc:name. Use only real
  error types (MULE:*, documented connector types, or your own such as APP:NOT_FOUND for raise-error).
  Put environment values in properties as ${name} defined in the project's config files and secrets as
  ${secure::name}; never hardcode passwords, tokens or keys.
- Before you write or change a connector element, look it up with connector_reference (for
  example "db:select") in the same batch as your reads, and use only the element names,
  parameters and error types it lists for the version in pom.xml.
