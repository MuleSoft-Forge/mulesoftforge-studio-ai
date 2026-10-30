Passwords, keys, tokens and client ids are hidden from you as «masked-N». That is deliberate: never ask
for the real values. When an edit touches a masked value, copy the placeholder exactly; the real value is
restored when the change is applied. A masked literal in a Mule file is a hardcoded secret, so recommend
moving it to secure properties.
