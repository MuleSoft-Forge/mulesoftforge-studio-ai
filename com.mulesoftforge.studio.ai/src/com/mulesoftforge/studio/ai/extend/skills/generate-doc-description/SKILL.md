---
name: generate-doc-description
description: Use when the user asks to document Mule XML files, flows, components or connectors, or to add or improve doc:description attributes.
---
<!--
Adapted for MuleSoftForge Studio AI from the generate-doc-description skill in @salesforce/mulesoft-vibes-skills
(https://github.com/mulesoft/mulesoft-dx), Copyright (c) 2026 Salesforce, Inc., Apache License 2.0
(http://www.apache.org/licenses/LICENSE-2.0). Changed: Studio's tools and proposals; shorter example.
-->

# Add doc:description to Mule XML

Add or improve `doc:description` attributes on the elements of the Mule configuration files in
`src/main/mule` (and `src/test/munit` when the user asks for tests too).

## Rules

- A description is one or two sentences, present tense, specific, and **at most 150 characters**.
- Change only `doc:description`. Keep every other attribute, element, namespace, CDATA block, comment and
  indentation exactly as it is. Never change `doc:name`.
- Keep an existing description that is accurate; replace one that is vague ("processes data") or wrong.
- The root element must declare `xmlns:doc="http://www.mulesoft.org/schema/mule/documentation"`; add it if
  it is missing.
- If you are unsure what an element does, write a conservative description or ask the user.

## Steps

1. Call `list_files` on `src/main/mule`. If it does not exist, tell the user this is not a Mule app project.
2. For each XML file, call `read_file` and work only from what it contains. Before you change it, list what
   you found: the flows and sub-flows by name, batch jobs, global configs and error handlers.
3. Describe each element that needs it:
   - flows, sub-flows and sources: the business process and what triggers it;
   - configs and connections: the system and the authentication type;
   - schedulers: the frequency in plain words;
   - loggers: what is logged and why;
   - `choice`, `when`, `otherwise`: the condition and the route;
   - transforms: source data, target format and purpose;
   - connector operations: the operation and the system;
   - error handlers: the errors caught and what happens to them;
   - MUnit tests, mocks and assertions: what is tested or checked.
4. Propose the file's changes with **one `edit_file` call per file**, a list of small edits that each add or
   replace one attribute. Do not rewrite the file with `write_file`.
5. Repeat for the next file, then call `check_project` and fix anything it reports.
6. Finish with the number of files changed and the descriptions added and updated, and anything you skipped.

## Example

```xml
<flow name="orders-sync" doc:name="Sync orders" doc:description="Copies Closed Won Salesforce opportunities to NetSuite as sales orders">
  <salesforce:modified-object-listener objectType="Opportunity" config-ref="salesforceConfig"
      doc:name="On opportunity change" doc:description="Polls Salesforce every minute for modified Opportunity records"/>
  <logger level="INFO" message="#[payload.Id]" doc:name="Log opportunity" doc:description="Logs the ID of the modified Opportunity"/>
</flow>
```
