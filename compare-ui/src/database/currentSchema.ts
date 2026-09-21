const compatiblePrefixes = ['jdbc:postgresql:', 'jdbc:gaussdb:', 'jdbc:opengauss:']
const compatibleDriverParts = ['postgresql', 'gaussdb', 'opengauss']

export function currentSchemaError(url: string, driver: string): string | null {
  const normalizedUrl = url.trim().toLowerCase()
  const normalizedDriver = driver.trim().toLowerCase()
  const compatible = compatiblePrefixes.some(prefix => normalizedUrl.startsWith(prefix))
    || compatibleDriverParts.some(part => normalizedDriver.includes(part))
  if (!compatible) return null

  const queryStart = url.indexOf('?')
  if (queryStart >= 0) {
    const parameters = new URLSearchParams(url.slice(queryStart + 1))
    for (const [key, value] of parameters.entries()) {
      if (key.toLowerCase() === 'currentschema' && value.trim()) return null
    }
  }
  return 'GaussDB/PostgreSQL 兼容连接的 JDBC URL 必须包含非空 currentSchema 参数'
}
