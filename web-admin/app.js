// Configuration
const REPO_OWNER = 'tonyddayvd';
const REPO_NAME = 'tv-playlist';
const BRANCH = 'main';

// State
let githubToken = localStorage.getItem('github_token') || '';
let currentClient = null;
let currentPlaylist = [];
let currentSha = null; // SHA of the client.json file (needed for update)

// DOM Elements
const screens = {
    login: document.getElementById('login-screen'),
    dashboard: document.getElementById('dashboard-screen'),
    editor: document.getElementById('editor-screen')
};

// --- Navigation ---
function showScreen(screenName) {
    Object.values(screens).forEach(s => s.classList.add('hidden'));
    screens[screenName].classList.remove('hidden');
}

function showToast(msg) {
    const toast = document.getElementById('toast');
    toast.innerText = msg;
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 3000);
}

// --- Auth ---
document.getElementById('btn-login').addEventListener('click', () => {
    const token = document.getElementById('github-token').value.trim();
    const save = document.getElementById('save-token').checked;

    if (!token) return showToast('Digite o token!');

    githubToken = token;
    if (save) localStorage.setItem('github_token', token);

    // Test Token
    fetchClients();
});

document.getElementById('btn-logout').addEventListener('click', () => {
    localStorage.removeItem('github_token');
    location.reload();
});

// --- GitHub API Helpers ---
async function ghFetch(path, options = {}) {
    const url = `https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/contents/${path}`;
    const headers = {
        'Authorization': `token ${githubToken}`,
        'Accept': 'application/vnd.github.v3+json',
        'Content-Type': 'application/json',
        ...options.headers
    };

    const response = await fetch(url, { ...options, headers });
    if (!response.ok) {
        if (response.status === 401) {
            showToast('Token Inválido!');
            showScreen('login');
            throw new Error('Auth Failed');
        }
        throw new Error(`GitHub Error: ${response.status}`);
    }
    return response.json();
}
async function loadClient(filename) {
    try {
        showToast(`Abrindo ${filename}...`);
        currentClient = filename;
        document.getElementById('client-title').innerText = filename.replace('.json', '');

        const data = await ghFetch(`playlists/${filename}`);
        currentSha = data.sha;

        // Decode Content (Base64 -> UTF8)
        const content = atob(data.content);
        const json = JSON.parse(content);

        currentPlaylist = json.playlist || [];
        renderPlaylist();
        showScreen('editor');
    } catch (e) {
        showToast('Erro ao abrir cliente');
        document.getElementById('btn-back').addEventListener('click', () => {
            showScreen('dashboard');
        });

        // --- Upload Logic ---
        const dropZone = document.getElementById('drop-zone');
        const fileInput = document.getElementById('file-input');

        dropZone.addEventListener('click', () => fileInput.click());

        fileInput.addEventListener('change', async (e) => {
            const files = Array.from(e.target.files);
            if (files.length === 0) return;

            showToast(`Iniciando upload de ${files.length} arquivos...`);

            for (const file of files) {
                await uploadFile(file);
            }

            renderPlaylist();
            showToast('Uploads concluídos! Clique em Salvar.');
        });

        async function uploadFile(file) {
            return new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.readAsDataURL(file);
                reader.onload = async () => {
                    try {
                        const base64Content = reader.result.split(',')[1];
                        const cleanName = sanitizeFilename(file.name);
                        const path = `playlists/${cleanName}`;

                        // 1. Upload to GitHub
                        await uploadToGithub(path, base64Content, `Upload via Web Admin: ${cleanName}`);

                        // 2. Add to Playlist Array
                        const type = file.type.startsWith('video') ? 'video' : 'image';
                        const duration = type === 'video' ? 30 : 10; // Default durations

                        const rawUrl = `https://raw.githubusercontent.com/${REPO_OWNER}/${REPO_NAME}/${BRANCH}/${path}`;

                        currentPlaylist.push({
                            id: cleanName,
                            type: type,
                            url: rawUrl,
                            duration: duration
                        });

                        resolve();
                    } catch (e) {
                        console.error(e);
                        showToast(`Erro no upload de ${file.name}`);
                        resolve(); // Continue others
                    }
                };
            });
        }

        function sanitizeFilename(name) {
            return name.replace(/[^a-zA-Z0-9._-]/g, '_').toLowerCase();
        }

        async function uploadToGithub(path, content, message) {
            const url = `https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/contents/${path}`;

            // Check if file exists to get SHA (for update)
            let sha = null;
            try {
                const existing = await fetch(url, {
                    headers: { 'Authorization': `token ${githubToken}` }
                });
                if (existing.ok) {
                    const json = await existing.json();
                    sha = json.sha;
                }
            } catch (e) { }

            const body = {
                message: message,
                content: content,
                branch: BRANCH
            };
            if (sha) body.sha = sha;

            const response = await fetch(url, {
                method: 'PUT',
                headers: {
                    'Authorization': `token ${githubToken}`,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(body)
            });

            if (!response.ok) throw new Error('Upload Failed');
        }

        // --- Save Playlist ---
        document.getElementById('btn-save').addEventListener('click', async () => {
            try {
                showToast('Salvando playlist...');

                // Construct new JSON
                const newJson = {
                    settings: {
                        orientation: "landscape",
                        transitionDuration: 1000
                    },
                    playlist: currentPlaylist
                };

                // Encode to Base64
                const content = btoa(JSON.stringify(newJson, null, 2));

                // Update File
                const url = `https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/contents/playlists/${currentClient}`;
                const body = {
                    message: `Update playlist ${currentClient}`,
                    content: content,
                    sha: currentSha,
                    branch: BRANCH
                };

                const response = await fetch(url, {
                    method: 'PUT',
                    headers: {
                        'Authorization': `token ${githubToken}`,
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(body)
                });

                if (response.ok) {
                    const data = await response.json();
                    currentSha = data.content.sha; // Update SHA for next save
                    showToast('✅ Playlist Salva com Sucesso!');
                } else {
                    throw new Error('Save Failed');
                }
            } catch (e) {
                showToast('Erro ao salvar: ' + e.message);
            }
        });

        // Auto-Login Check
        if (githubToken) {
            fetchClients();
        }
