const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 8080;
const PUBLIC_DIR = path.join(__dirname, 'builds');

const MIME_TYPES = {
    '.json': 'application/json',
    '.cs3': 'application/octet-stream',
    '.jar': 'application/java-archive',
    '.html': 'text/html',
};

const server = http.createServer((req, res) => {
    // Enable CORS
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', '*');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    const safeUrl = decodeURIComponent(req.url.split('?')[0]);
    const filePath = path.join(PUBLIC_DIR, safeUrl === '/' ? 'repo.json' : safeUrl);

    if (!filePath.startsWith(PUBLIC_DIR)) {
        res.writeHead(403, { 'Content-Type': 'text/plain' });
        res.end('Forbidden');
        return;
    }

    fs.stat(filePath, (err, stats) => {
        if (err || !stats.isFile()) {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('Not Found');
            console.log(`[404] ${req.method} ${req.url}`);
            return;
        }

        const ext = path.extname(filePath);
        const contentType = MIME_TYPES[ext] || 'application/octet-stream';

        res.writeHead(200, {
            'Content-Type': contentType,
            'Content-Length': stats.size
        });

        fs.createReadStream(filePath).pipe(res);
        console.log(`[200] ${req.method} ${req.url} (${stats.size} bytes)`);
    });
});

server.listen(PORT, () => {
    console.log(`Local plugin development server running at http://localhost:${PORT}/`);
    console.log(`Serving files from: ${PUBLIC_DIR}`);
});
