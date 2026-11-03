Studio metadata (DataSense):
- Warnings such as "Auto-Coercing type from Any", "Unable to resolve reference of: attributes" or an
  unknown payload type in a Transform mean the processor has no input metadata. The script is
  usually fine; say so, and fix the metadata rather than the script. Studio checks a flow called by
  flow-ref on its own, so a private flow needs its own metadata even when the caller has it.
- Metadata lives in src/main/resources/application-types.xml. Read it first if it exists and add to
  it; there is one per project. Studio writes it in this form:
  ```xml
  <?xml version='1.0' encoding='UTF-8'?>
  <types:mule xmlns:types="http://www.mulesoft.org/schema/mule/types">
    <types:catalog>
      <types:type name="customer-input" format="json">
        <types:shape format="weave" example="examples/customer-input.json"><![CDATA[%dw 2.0

  type customer_input = {|
    customerId: String,
    "content-type": String
  |}

  ]]></types:shape>
      </types:type>
      <types:type name="order-output" format="json">
        <types:shape format="json-schema" location="metadata/order-output.json"/>
      </types:type>
    </types:catalog>
    <types:enrichment select="#<doc:id of the processor>">
      <types:processor-declaration>
        <types:input-event>
          <types:message>
            <types:payload type="customer-input"/>
            <types:attributes type="…"/>
          </types:message>
        </types:input-event>
      </types:processor-declaration>
    </types:enrichment>
  </types:mule>
  ```
- A weave shape names its type like the catalog entry with - as _. Quote every key that is not a plain
  identifier ("content-type", "user-agent"); one unquoted key breaks the whole shape. The example or
  json-schema file goes under src/main/resources at the path the shape names.
- select is # plus the doc:id of the processor the metadata describes; give that processor a doc:id
  (a new UUID) if it has none. Use input-event on the processor that reads the data (the Transform),
  and output-event, with the same message parts, on the flow or processor whose result it is.
- That covers JSON types. For XML, CSV or Java types you have no example of Studio's form: say so, and
  suggest the user defines the type once in Studio (Transform Message > Define metadata) so you can
  follow it.
