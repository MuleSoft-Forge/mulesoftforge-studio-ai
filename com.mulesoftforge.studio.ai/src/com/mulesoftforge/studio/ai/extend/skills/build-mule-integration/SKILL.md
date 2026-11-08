---
name: build-mule-integration
description: Use before you create or change a Mule flow, sub-flow, connector configuration or integration in this Studio project, including targeted changes to one router, error handler, transform or connector operation. Not needed for questions that change nothing.
---
<!--
Adapted for MuleSoftForge Studio AI from the build-mule-integration skill in @salesforce/mulesoft-vibes-skills
(https://github.com/mulesoft/mulesoft-dx), Copyright (c) 2026 Salesforce, Inc., Apache License 2.0
(http://www.apache.org/licenses/LICENSE-2.0). Changed: rewritten for Studio's tools and proposals; no CLI.
-->

# Build or change a Mule integration in Studio

Work in two phases: a short technical design, then the build. Every change is a proposal the user reviews, so
the build must pass the project checks before you finish.

## Phase 1: Design

1. **Read before you design.** Read `pom.xml`, the Mule XML files under `src/main/mule` that the change touches,
   and the property files they use. For a change to an existing flow, also read the flows it calls with
   `flow-ref`. Do not design from memory of a typical project.
2. **Name every system.** List each external system the request names (Salesforce, SAP, a database, a queue,
   an HTTP API) and the trigger: an HTTP listener, a scheduler, a connector's listener, or a flow called by
   another flow.
3. **Use a dedicated connector when one exists.** For each system, call `search_connectors` with its name
   (all of them in one batch). It returns the best Exchange connectors with their latest release, and says
   which the project already uses. Never pre-judge that a system has no connector; search it. Use
   `http:request` only for a system the search finds no connector for, and say so. Then call
   `connector_reference` for a connector the project already depends on, to get its exact operations.
4. **Pick operations and connection providers from the reference**, not from memory: element names,
   required attributes and the connection type (basic, OAuth, token) as `connector_reference` lists them.
5. **For a new integration, or a change across several flows, confirm the design first.** Call
   `ask_followup_question` with a short design: trigger, systems and connectors, each flow and what it does,
   the configs and properties to add, and how errors are handled. Offer the choices that matter (for example
   which connection type). Skip this for a small, targeted change the user described exactly.

## Phase 2: Build

6. **Track the work.** For more than two files, call `update_todo_list` with one item per file or step.
7. **Configuration first.** Add global configs once, in the file the project keeps them in (often
   `global.xml`). Put hosts, ports, user names and paths in the property file with `${...}` references; put
   passwords and keys in secure properties, never as literal values.
8. **Dependencies.** A connector the project does not depend on yet needs its dependency in `pom.xml`. Propose
   that edit too, exactly as `search_connectors` gave it (group, artifact, version, `mule-plugin` classifier);
   never invent a version. Until the user accepts it and Studio downloads the connector, `connector_reference`
   cannot list its operations: take names from its documentation with `fetch_docs` and say which you could
   not check.
9. **Flows.** Generate complete flows: source, processors, transforms, and an error handler that logs and
   either propagates or continues on purpose. Use the namespace and schema location of every prefix you use.
   Keep DataWeave in `ee:transform` with an explicit `output` directive.
10. **Canvas names.** Give each new processor a `doc:name` that says what it does in business terms, and a
    `doc:description` of at most 150 characters.
11. **Edits, not rewrites.** Change existing files with `edit_file` and small, exact edits. Use `write_file`
    only for new files.

## Finish only when the checks pass

12. Call `check_project`. Fix every problem it reports in the files you proposed, then check again. Do not
    finish while it still reports a problem your changes introduced.
13. Answer with what you proposed, file by file, what the user must still do (for example fill in a secure
    property value or add a connector to the project in Studio), and what you could not verify.
