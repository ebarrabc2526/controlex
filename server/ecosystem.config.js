require('dotenv').config({ path: __dirname + '/.env' });

module.exports = {
    apps: [{
        name: 'controlex',
        script: 'server.js',
        cwd: '/home/ebarrab/pro/controlex',
        env: {
            PORT: 4000,
            NODE_ENV: 'production',
            GOOGLE_CLIENT_ID: process.env.GOOGLE_CLIENT_ID,
            GOOGLE_CLIENT_SECRET: process.env.GOOGLE_CLIENT_SECRET,
            SESSION_SECRET: process.env.SESSION_SECRET,
            ALLOWED_EMAIL: process.env.ALLOWED_EMAIL,
            CONTROLEX_API_KEY: process.env.CONTROLEX_API_KEY
        },
        restart_delay: 5000,
        max_restarts: 10
    }]
};
