/**
 * Renders the WarpGate launcher icon into the Android mipmap folders.
 *
 * The master artwork lives in ic_launcher.svg (108x108 adaptive icon viewport).
 * This script produces the legacy PNG mipmaps, which are still used by launchers
 * and by the Play Store listing. The adaptive icon itself is vector based
 * (res/mipmap-anydpi-v26/ic_launcher.xml) and needs no rasterisation.
 *
 * Usage:
 *   npm i sharp            # once, in the repository root
 *   node warpgate/design/render-icons.mjs
 */
import { mkdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'

const here = dirname(fileURLToPath(import.meta.url))
const resDir = join(here, '..', 'app', 'src', 'main', 'res')
const master = readFileSync(join(here, 'ic_launcher.svg'), 'utf8')

/** Launcher icon sizes per density bucket. */
const densities = {
  mdpi: 48,
  hdpi: 72,
  xhdpi: 96,
  xxhdpi: 144,
  xxxhdpi: 192,
}

const SQUARE_SHAPE = '<rect x="0" y="0" width="108" height="108" rx="24" />'
const ROUND_SHAPE = '<circle cx="54" cy="54" r="54" />'

/**
 * Swaps the outer clip shape of the master artwork.
 * @param {'square'|'round'} shape
 */
function withShape(shape) {
  const clip = shape === 'round' ? ROUND_SHAPE : SQUARE_SHAPE
  return master.replace(SQUARE_SHAPE, clip)
}

for (const [density, size] of Object.entries(densities)) {
  const outDir = join(resDir, `mipmap-${density}`)
  mkdirSync(outDir, { recursive: true })

  for (const shape of ['square', 'round']) {
    const name = shape === 'round' ? 'ic_launcher_round.png' : 'ic_launcher.png'
    await sharp(Buffer.from(withShape(shape)), { density: 384 })
      .resize(size, size, { fit: 'fill' })
      .png({ compressionLevel: 9 })
      .toFile(join(outDir, name))
    console.log(`${name} ${density} ${size}x${size}`)
  }
}
