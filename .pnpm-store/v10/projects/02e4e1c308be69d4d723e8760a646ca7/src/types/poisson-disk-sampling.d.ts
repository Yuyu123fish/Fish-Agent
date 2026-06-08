declare module 'poisson-disk-sampling' {
  interface Options {
    shape: [number, number]
    minDistance: number
    maxDistance?: number
    tries?: number
    distanceFunction?: (point: number[]) => number
    bias?: number
  }

  class PoissonDiskSampling {
    constructor(options: Options)
    fill(): number[][]
    getAllPoints(): number[][]
    addRandomPoints(count: number): number[][]
    next(): number[] | null
    reset(): void
  }

  export default PoissonDiskSampling
}
