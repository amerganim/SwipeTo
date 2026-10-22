// Validates every test vector against docs/schemas and checks coverage:
// each message type needs at least one valid and one invalid vector.
import { readdirSync, readFileSync } from "node:fs";
import { join, dirname, basename } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "docs");
const schemaDir = join(root, "schemas");
const vectorDir = join(root, "test-vectors");

const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
for (const file of readdirSync(schemaDir).filter((f) => f.endsWith(".schema.json"))) {
  ajv.addSchema(JSON.parse(readFileSync(join(schemaDir, file), "utf8")), file);
}
const validate = ajv.getSchema("message.schema.json");
const messageTypes = JSON.parse(readFileSync(join(schemaDir, "message.schema.json"), "utf8")).properties.type.enum;

const readVectors = (kind) =>
  readdirSync(join(vectorDir, kind))
    .filter((f) => f.endsWith(".json"))
    .map((f) => ({ name: basename(f, ".json"), body: JSON.parse(readFileSync(join(vectorDir, kind, f), "utf8")) }));

// Vector files are named <type>[-<case>].json; "envelope-*" covers rules shared by all types.
const typeOf = (name) => name.split("-")[0];

let failures = 0;
const fail = (msg) => {
  failures++;
  console.error(`FAIL ${msg}`);
};

const valid = readVectors("valid");
for (const { name, body } of valid) {
  if (!validate(body)) fail(`valid/${name}: ${ajv.errorsText(validate.errors)}`);
  else if (body.type !== typeOf(name)) fail(`valid/${name}: type "${body.type}" does not match file name`);
  else console.log(`ok   valid/${name}`);
}

const invalid = readVectors("invalid");
for (const { name, body } of invalid) {
  if (validate(body)) fail(`invalid/${name}: was accepted`);
  else console.log(`ok   invalid/${name} (rejected)`);
}

for (const type of messageTypes) {
  if (!valid.some((v) => typeOf(v.name) === type)) fail(`no valid vector for ${type}`);
  if (!invalid.some((v) => typeOf(v.name) === type)) fail(`no invalid vector for ${type}`);
}

console.log(`\n${valid.length} valid, ${invalid.length} invalid, ${messageTypes.length} message types`);
if (failures) {
  console.error(`${failures} failure(s)`);
  process.exit(1);
}
console.log("All test vectors pass.");
