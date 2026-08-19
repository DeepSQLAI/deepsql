/**
 * Lightweight unit tests for multi-schema name helpers.
 * Run: node --test src/lib/schemaNames.test.js
 */
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  canonicalTableReference,
  objectKey,
  qualifyForSql,
  parseQualifiedName,
  connectionHasMultipleSchemas,
  isDefaultSchema,
} from './schemaNames.js'

describe('schemaNames', () => {
  it('keeps default schemas bare', () => {
    assert.equal(canonicalTableReference({ schema: 'public', name: 'orders' }), 'orders')
    assert.equal(canonicalTableReference({ schema: 'dbo', name: 'orders' }), 'orders')
    assert.equal(isDefaultSchema('public'), true)
  })

  it('qualifies non-default schemas', () => {
    assert.equal(canonicalTableReference({ schema: 'crm', name: 'orders' }), 'crm.orders')
    assert.equal(objectKey({ schema: 'crm', name: 'orders' }), 'crm.orders')
    assert.equal(objectKey({ schema: 'sales', name: 'orders' }), 'sales.orders')
  })

  it('builds SQL FROM targets', () => {
    assert.equal(qualifyForSql({ schema: 'public', name: 'orders' }), 'orders')
    assert.equal(qualifyForSql({ schema: 'crm', name: 'orders' }), 'crm.orders')
  })

  it('parses qualified names', () => {
    assert.deepEqual(parseQualifiedName('crm.orders'), { schema: 'crm', name: 'orders' })
    assert.deepEqual(parseQualifiedName('orders'), { schema: '', name: 'orders' })
  })

  it('detects multi-schema connections', () => {
    assert.equal(
      connectionHasMultipleSchemas([
        { schema: 'crm', name: 'a' },
        { schema: 'sales', name: 'b' },
      ]),
      true,
    )
    assert.equal(
      connectionHasMultipleSchemas([
        { schema: 'public', name: 'a' },
        { schema: 'public', name: 'b' },
      ]),
      false,
    )
  })
})
