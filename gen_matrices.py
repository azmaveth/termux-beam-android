#!/usr/bin/env python3
"""
Extract forward PCA weight matrix from firmware C source and generate
inverse DCT matrix. Both are saved as raw float32 binary files to be
loaded as Android assets by BuddieService.

PCA inverse: for orthogonal PCA, the forward weight matrix IS the inverse.
  Forward: spectrum(1x257) → compressed(1x47) via projection
  Inverse: compressed(1x47) × pca_weight(47x257) → spectrum(1x257)

DCT inverse: standard DCT-III (inverse of DCT-II) with the same
  normalization as the firmware's dct_transform().
"""
import re, struct, math, sys

PCA_SIZE = 47
DCT_SIZE = 257

def extract_pca_matrix(c_source_path):
    """Parse the __fp16 pca_weight[47][257] = {...} from C source."""
    with open(c_source_path, 'r') as f:
        text = f.read()

    # Find the pca_weight array initialization
    match = re.search(r'pca_weight\[.*?\]\[.*?\]\s*=\s*\{', text)
    if not match:
        raise ValueError("Could not find pca_weight array in C source")

    start = match.end()
    # Find the closing }; — we need to match nested braces for rows
    depth = 1
    pos = start
    while depth > 0 and pos < len(text):
        if text[pos] == '{':
            depth += 1
        elif text[pos] == '}':
            depth -= 1
        pos += 1
    array_text = text[start:pos-1]

    # Parse row by row
    rows = re.findall(r'\{([^}]+)\}', array_text)
    if len(rows) != PCA_SIZE:
        raise ValueError(f"Expected {PCA_SIZE} rows, got {len(rows)}")

    matrix = []
    for i, row in enumerate(rows):
        values = [float(x.strip()) for x in row.split(',') if x.strip()]
        if len(values) != DCT_SIZE:
            raise ValueError(f"Row {i}: expected {DCT_SIZE} values, got {len(values)}")
        matrix.append(values)

    return matrix

def generate_idct_matrix():
    """
    Generate inverse DCT matrix (DCT-III) matching the firmware's DCT-II.

    The firmware's dct_transform uses:
      ortho_x_factor = sqrt(2.0)
      ortho_y_factor = 0.5 * sqrt(2.0 / (DCT_SIZE - 1))

    Forward DCT-II (N = DCT_SIZE = 257):
      X[0] = ortho_y_factor * sum_{n=0}^{N-1} x[n]
      X[k] = ortho_x_factor * ortho_y_factor * sum_{n=0}^{N-1} x[n] * cos(pi*k*(2n+1)/(2N))

    Inverse (DCT-III):
      x[n] = (1/ortho_y_factor/N) * X[0]
           + (1/ortho_x_factor/ortho_y_factor/N) * 2 * sum_{k=1}^{N-1} X[k] * cos(pi*k*(2n+1)/(2N))

    But since we're building a matrix, IDCT[n][k] gives the coefficient
    to multiply X[k] by to get x[n].
    """
    N = DCT_SIZE
    ortho_x = math.sqrt(2.0)
    ortho_y = 0.5 * math.sqrt(2.0 / (N - 1))

    # Build the forward DCT matrix F such that X = F @ x
    # F[k][n] = ortho_y * cos(pi*k*(2n+1)/(2N))  for k=0
    # F[k][n] = ortho_x * ortho_y * cos(pi*k*(2n+1)/(2N))  for k>0
    F = []
    for k in range(N):
        row = []
        for n in range(N):
            c = math.cos(math.pi * k * (2*n + 1) / (2*N))
            if k == 0:
                row.append(ortho_y * c)
            else:
                row.append(ortho_x * ortho_y * c)
        F.append(row)

    # The inverse is F^T scaled appropriately for orthogonal DCT.
    # For orthogonal DCT: F^{-1} = F^T (the transpose)
    # But our F isn't exactly orthogonal due to the custom scaling.
    # Let's verify: For orthogonal DCT-II, the standard normalization is:
    #   F[0][n] = 1/sqrt(N)
    #   F[k][n] = sqrt(2/N) * cos(pi*k*(2n+1)/(2N))
    # Our scaling: ortho_y = 0.5*sqrt(2/(N-1)) ≈ sqrt(1/(2(N-1)))
    # This doesn't match standard orthogonal DCT, so we can't just transpose.
    # Instead, compute the actual inverse via solving the linear system.

    # For a practical approach: since the Flutter app uses a stored iDCT matrix,
    # and the DCT basis vectors are orthogonal (just not normalized to unit length),
    # the inverse is: IDCT = F^T @ diag(1 / (F @ F^T diagonal))
    # Or more simply: for each k, the basis vector has squared norm
    # ||f_k||^2 = sum_n F[k][n]^2, and IDCT[n][k] = F[k][n] / ||f_k||^2

    # Compute squared norms of each row
    norms_sq = []
    for k in range(N):
        ns = sum(F[k][n]**2 for n in range(N))
        norms_sq.append(ns)

    # IDCT[n][k] = F[k][n] / norms_sq[k]
    IDCT = []
    for n in range(N):
        row = []
        for k in range(N):
            row.append(F[k][n] / norms_sq[k])
        IDCT.append(row)

    # Verify: x = IDCT @ (F @ x) should be identity
    # Quick spot check with a unit vector
    x_test = [0.0] * N
    x_test[1] = 1.0
    X_test = [sum(F[k][n] * x_test[n] for n in range(N)) for k in range(N)]
    x_rec = [sum(IDCT[n][k] * X_test[k] for k in range(N)) for n in range(N)]
    err = max(abs(x_rec[n] - x_test[n]) for n in range(N))
    print(f"IDCT verification: max reconstruction error = {err:.2e}")
    if err > 1e-6:
        print("WARNING: IDCT reconstruction error is large!")

    return IDCT

def save_matrix_binary(matrix, path, rows, cols):
    """Save matrix as raw float32 little-endian binary."""
    with open(path, 'wb') as f:
        for r in range(rows):
            for c in range(cols):
                f.write(struct.pack('<f', matrix[r][c]))
    print(f"Saved {rows}x{cols} matrix to {path} ({rows*cols*4} bytes)")

def main():
    firmware_path = sys.argv[1] if len(sys.argv) > 1 else \
        "/data/data/com.termux/files/home/reversing/buddie-src/Firmware-JL701N/cpu/br28/fft_and_pca.c"
    assets_dir = "/data/data/com.termux/files/home/BeamApp/assets"

    print("=== Extracting PCA weight matrix ===")
    pca = extract_pca_matrix(firmware_path)
    print(f"PCA matrix: {len(pca)} x {len(pca[0])}")
    print(f"  Row 0 first 5: {pca[0][:5]}")
    print(f"  Row 0 last 5:  {pca[0][-5:]}")

    print("\n=== Generating inverse DCT matrix ===")
    idct = generate_idct_matrix()
    print(f"IDCT matrix: {len(idct)} x {len(idct[0])}")

    print("\n=== Saving matrices ===")
    save_matrix_binary(pca, f"{assets_dir}/ipca_weight.bin", PCA_SIZE, DCT_SIZE)
    save_matrix_binary(idct, f"{assets_dir}/idct_weight.bin", DCT_SIZE, DCT_SIZE)

    print("\nDone!")

if __name__ == '__main__':
    main()
