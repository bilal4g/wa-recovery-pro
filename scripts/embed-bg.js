import fs from 'fs';

const bgBuffer = fs.readFileSync('bg.jpeg');
const bgBase64 = bgBuffer.toString('base64');
const dataUri = `data:image/jpeg;base64,${bgBase64}`;

let css = fs.readFileSync('src/css/app.css', 'utf8');

// Replace body background
css = css.replace(
  /body\s*\{[^}]*background:[^;]+;/s,
  `body {\n  font-family: var(--font-primary);\n  background: #0a0f1c url('${dataUri}') no-repeat center center fixed;\n  background-size: cover;`
);

fs.writeFileSync('src/css/app.css', css);
console.log('✅ Successfully embedded bg.jpeg as Base64 in src/css/app.css');
