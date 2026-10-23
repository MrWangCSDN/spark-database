import { describe, expect, it } from 'vitest'
import { currentSchemaError } from './currentSchema'

describe('currentSchemaError', () => {
  it.each([
    ['jdbc:postgresql://db/core', 'org.postgresql.Driver'],
    ['jdbc:gaussdb://db/core?currentSchema=', 'com.huawei.Driver'],
    ['jdbc:opengauss://db/core?currentSchema=%20', 'custom.Driver'],
    ['jdbc:custom://db/core?ssl=true', 'org.opengauss.Driver']
  ])('requires a nonblank schema for %s', (url, driver) => {
    expect(currentSchemaError(url, driver)).toMatch(/currentSchema/)
  })

  it('accepts a case-insensitive parameter among other parameters', () => {
    expect(currentSchemaError(
      'jdbc:postgresql://db/core?ssl=true&CURRENTSCHEMA=business&applicationName=compare',
      'org.postgresql.Driver'
    )).toBeNull()
  })

  it.each([
    ['jdbc:oracle:thin:@db:1521/core', 'oracle.jdbc.OracleDriver'],
    ['jdbc:mysql://db/core', 'com.mysql.cj.jdbc.Driver']
  ])('does not apply to %s', (url, driver) => {
    expect(currentSchemaError(url, driver)).toBeNull()
  })
})

describe('GDB/MySQL compatibility', () => {
  it.each([
    ['jdbc:gdb://db/core', 'com.mysql.jdbc.Driver'],
    ['jdbc:mysql://db/core', 'vendor.gdb.Driver']
  ])('does not require currentSchema for %s', (url, driver) => {
    expect(currentSchemaError(url, driver)).toBeNull()
  })
})
