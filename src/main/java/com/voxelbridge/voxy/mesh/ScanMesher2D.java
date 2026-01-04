package com.voxelbridge.voxy.mesh;

/**
 * 32x32 scanline greedy mesher (ported from voxy-dev).
 * Emits maximal rectangles for a 2D grid fed row-major via putNext/skip.
 */
public abstract class ScanMesher2D {

    private static final int MAX_SIZE = 16;

    private final long[] rowData = new long[32];
    private final int[] rowLength = new int[32];
    private final int[] rowDepth = new int[32];
    private int rowBitset = 0;

    private int currentIndex = 0;
    private int currentSum = 0;
    private long currentData = 0;

    /**
     * Feed the next cell in row-major order (x increases fastest, then z).
     * Value 0 means empty; any other value is treated as mergeable if equal.
     */
    public final void putNext(long data) {
        int idx = (this.currentIndex++) & 31;

        if (idx == 0) {
            if (this.currentData != 0) {
                if ((this.rowBitset & (1 << 31)) != 0) {
                    this.emitQuad(31, ((this.currentIndex - 1) >> 5) - 1, this.rowLength[31], this.rowDepth[31], this.rowData[31]);
                }
                this.rowBitset |= 1 << 31;
                this.rowLength[31] = this.currentSum;
                this.rowDepth[31] = 1;
                this.rowData[31] = this.currentData;
            }

            this.currentData = data;
            this.currentSum = 0;
        }

        if (data != this.currentData || this.currentSum == MAX_SIZE) {
            if (this.currentData != 0) {
                int prev = idx - 1;
                this.rowDepth[prev] = 1;
                this.rowLength[prev] = this.currentSum;
                this.rowData[prev] = this.currentData;
                this.rowBitset |= 1 << prev;
            }

            this.currentData = data;
            this.currentSum = 0;
        }
        this.currentSum++;

        boolean isSet = (this.rowBitset & (1 << idx)) != 0;
        boolean causedByDepthMax = false;
        if (this.currentData != 0 &&
                isSet &&
                this.rowLength[idx] == this.currentSum &&
                this.rowData[idx] == this.currentData) {
            int depth = ++this.rowDepth[idx];
            this.currentSum = 0;
            this.currentData = 0;
            if (depth != MAX_SIZE) {
                return;
            }
            causedByDepthMax = true;
        }

        if (isSet) {
            this.emitQuad(idx & 31, ((this.currentIndex - 1) >> 5) - (causedByDepthMax ? 0 : 1), this.rowLength[idx], this.rowDepth[idx], this.rowData[idx]);
            this.rowBitset &= ~(1 << idx);
        }
    }

    private void emitRanged(int mask) {
        int rowSet = this.rowBitset & mask;
        this.rowBitset &= ~mask;
        while (rowSet != 0) {
            int index = Integer.numberOfTrailingZeros(rowSet);
            rowSet &= ~Integer.lowestOneBit(rowSet);
            this.emitQuad(index, (this.currentIndex >> 5) - 1, this.rowLength[index], this.rowDepth[index], this.rowData[index]);
        }
    }

    public final void skip(int count) {
        if (count == 0) return;
        if (this.currentData != 0) {
            this.putNext(0);
            count--;
        }
        if (count > 0) {
            int mask = (int) ((1L << Math.min(32, count)) - 1) << (this.currentIndex & 31);
            this.emitRanged(mask);
            this.currentIndex += count;
        }

    }

    public final void reset() {
        this.rowBitset = 0;
        this.currentSum = 0;
        this.currentData = 0;
        this.currentIndex = 0;
    }

    public final void endRow() {
        if ((this.currentIndex & 31) != 0) {
            this.skip(32 - (this.currentIndex & 31));
        }
    }

    public final void finish() {
        if (this.currentIndex != 0) {
            this.skip(32 - (this.currentIndex & 31));
            this.emitRanged(-1);
        }

        this.reset();
    }

    protected abstract void emitQuad(int x, int z, int length, int width, long data);
}
