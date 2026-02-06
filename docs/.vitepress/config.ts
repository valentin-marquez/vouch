import { defineConfig } from 'vitepress'

function sidebarGuide(version: string) {
  return [
    {
      text: 'Getting Started',
      collapsed: false,
      items: [
        { text: 'Installation', link: `/${version}/installation` },
        { text: 'Quick Start', link: `/${version}/quick-start` },
      ]
    },
    {
      text: 'Configuration',
      collapsed: false,
      items: [
        { text: 'Overview', link: `/${version}/configuration/` },
        { text: 'Branding', link: `/${version}/configuration/branding` },
        { text: 'Database', link: `/${version}/configuration/database` },
        { text: 'Authentication', link: `/${version}/configuration/authentication` },
        { text: 'Sessions', link: `/${version}/configuration/sessions` },
        { text: 'TOTP (2FA)', link: `/${version}/configuration/totp` },
        { text: 'Cryptography', link: `/${version}/configuration/cryptography` },
        { text: 'User Interface', link: `/${version}/configuration/ui` },
        { text: 'Miscellaneous', link: `/${version}/configuration/misc` },
      ]
    },
    {
      text: 'Usage',
      collapsed: false,
      items: [
        { text: 'Commands', link: `/${version}/commands` },
        { text: 'Permissions', link: `/${version}/permissions` },
        { text: 'Auth Modes', link: `/${version}/auth-modes` },
      ]
    },
    {
      text: 'Deep Dive',
      collapsed: false,
      items: [
        { text: 'Database', link: `/${version}/database` },
        { text: 'Security', link: `/${version}/security` },
        { text: 'Language System', link: `/${version}/language-system` },
        { text: 'User Experience', link: `/${version}/user-experience` },
        { text: 'Multi-Platform', link: `/${version}/multi-platform` },
      ]
    },
    {
      text: 'For Developers',
      collapsed: true,
      items: [
        { text: 'API Reference', link: `/${version}/api` },
      ]
    },
    {
      text: 'Help',
      collapsed: true,
      items: [
        { text: 'Troubleshooting', link: `/${version}/troubleshooting` },
        { text: 'FAQ', link: `/${version}/faq` },
      ]
    },
  ]
}

export default defineConfig({
  base: '/',
  title: 'Vouch',
  description: 'Secure server-side authentication for Minecraft.',
  head: [
    ['link', { rel: 'icon', href: '/icon.png' }],
  ],
  cleanUrls: true,
  lastUpdated: true,

  themeConfig: {
    logo: '/icon.png',
    siteTitle: 'Vouch',

    nav: [
      { text: 'Home', link: '/' },
      {
        text: 'Docs',
        link: '/1.21.1/',
      },
      {
        text: '1.21.1',
        items: [
          { text: '1.21.1', link: '/1.21.1/' },
          // Future versions will be added here
          // { text: '1.26.1', link: '/1.26.1/' },
        ]
      },
      { text: 'Changelog', link: '/changelog' },
      { text: 'Contributing', link: '/contributing' },
    ],

    sidebar: {
      '/1.21.1/': sidebarGuide('1.21.1'),
      // Future versions:
      // '/1.26.1/': sidebarGuide('1.26.1'),
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/valentin-marquez/vouch' },
      {
        icon: {
          svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor"><path d="M23.881 8.948c-.773-4.085-4.859-4.593-4.859-4.593H.723c-.604 0-.679.798-.679.798s-.082 7.324-.022 11.822c.164 2.424 2.586 2.672 2.586 2.672s8.267-.023 11.966-.049c2.438-.426 2.683-2.566 2.658-3.734 4.352.24 7.422-2.831 6.649-6.916zm-11.062 3.511c-1.246 1.453-4.011 3.976-4.011 3.976s-.121.119-.31.023c-.076-.057-.108-.09-.108-.09-.443-.441-3.368-3.049-4.034-3.954-.709-.965-1.041-2.7-.091-3.71.951-1.01 3.005-1.086 4.363.407 0 0 1.565-1.782 3.468-.963 1.904.82 1.832 3.011.723 4.311z"/></svg>'
        },
        link: 'https://ko-fi.com/nozzdev',
        ariaLabel: 'Support on Ko-fi'
      },
    ],

    search: {
      provider: 'local',
    },

    editLink: {
      pattern: 'https://github.com/valentin-marquez/vouch/edit/main/docs/:path',
      text: 'Edit this page on GitHub',
    },

    footer: {
      message: 'All Rights Reserved - Source Available. · <a href="https://ko-fi.com/nozzdev" target="_blank">☕ Support on Ko-fi</a>',
      copyright: '© 2026 Vouch — Server-side authentication for Minecraft.',
    },
  },
})
