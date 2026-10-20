import { defineConfig } from 'vitest/config';

/**
 * Plain vitest (no @cloudflare/vitest-pool-workers). The pool plugin
 * needs `workerd` and a wrangler.toml-aware loader; on Windows that
 * adds dependency friction for what is, here, just route-level
 * validation + sanitization testing. Route handlers receive an `Env`
 * argument whose D1 / R2 methods are stubbed in the test files
 * themselves.
 */
export default defineConfig({
    test: {
        include: ['tests/**/*.test.ts'],
        environment: 'node',
        globals: false,
        reporters: ['default'],
        testTimeout: 10_000,
    },
});