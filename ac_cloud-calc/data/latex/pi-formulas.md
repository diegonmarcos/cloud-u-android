# The π Formulas

Merged after the owner's list (100-formulas.md, which stays verbatim) by gen-formulas.mjs as the category "π (Pi)".
A heading may end in `{#id}` to fix the formula id (the plot specs in plots.json are keyed by it).
`Includes: #N, ...` lists owner formulas that also belong here; they are referenced, not copied.

---

## 10. π (Pi)

Includes: #2, #11, #15

### 101. Leibniz series {#pi-leibniz-series}
$$\frac{\pi}{4} = \sum_{k=0}^{\infty} \frac{(-1)^k}{2k+1} = 1 - \frac{1}{3} + \frac{1}{5} - \frac{1}{7} + \cdots$$
The alternating sum of the odd reciprocals, found by Madhava and Leibniz; it is $\arctan 1$ and converges very slowly, gaining about one digit per tenfold increase in terms.

### 102. Nilakantha series {#pi-nilakantha-series}
$$\pi = 3 + \frac{4}{2\cdot 3\cdot 4} - \frac{4}{4\cdot 5\cdot 6} + \frac{4}{6\cdot 7\cdot 8} - \cdots$$
A 15th-century series that starts from 3 and adds alternating cubic-denominator corrections; far faster than Leibniz.

### 103. Wallis product {#pi-wallis-product}
$$\frac{\pi}{2} = \prod_{k=1}^{\infty} \frac{4k^2}{4k^2 - 1} = \frac{2}{1}\cdot\frac{2}{3}\cdot\frac{4}{3}\cdot\frac{4}{5}\cdot\frac{6}{5}\cdot\frac{6}{7}\cdots$$
John Wallis's 1656 infinite product, the first exact expression for $\pi$ as a limit of rational numbers.

### 104. Viète's formula {#pi-viete-product}
$$\frac{2}{\pi} = \frac{\sqrt{2}}{2}\cdot\frac{\sqrt{2+\sqrt{2}}}{2}\cdot\frac{\sqrt{2+\sqrt{2+\sqrt{2}}}}{2}\cdots$$
The first infinite expression for $\pi$ (1593), built from nested square roots; each factor is $\cos(\pi/2^{k+1})$, the polygon-doubling of Archimedes.

### 105. Machin's formula {#pi-machin-formula}
$$\frac{\pi}{4} = 4\arctan\frac{1}{5} - \arctan\frac{1}{239}$$
John Machin's 1706 identity; two rapidly converging arctangent series computed $\pi$ to 100 digits and remained the workhorse for two centuries.

### 106. Bailey–Borwein–Plouffe formula {#pi-bbp-formula}
$$\pi = \sum_{k=0}^{\infty} \frac{1}{16^k}\left(\frac{4}{8k+1} - \frac{2}{8k+4} - \frac{1}{8k+5} - \frac{1}{8k+6}\right)$$
The 1995 spigot formula that yields the $n$-th hexadecimal digit of $\pi$ without computing the earlier ones.

### 107. Ramanujan's 1914 series {#pi-ramanujan-1914-series}
$$\frac{1}{\pi} = \frac{2\sqrt{2}}{9801}\sum_{k=0}^{\infty} \frac{(4k)!\,(1103 + 26390k)}{(k!)^4\,396^{4k}}$$
Srinivasa Ramanujan's rapidly convergent series; every term adds about eight correct digits, and it is the ancestor of the Chudnovsky algorithm.

### 108. Gauss–Legendre algorithm {#pi-gauss-legendre-iteration}
$$a_{n+1} = \frac{a_n + b_n}{2},\quad b_{n+1} = \sqrt{a_n b_n},\quad \pi \approx \frac{4\,a_N^2}{1 - \sum_{j=1}^{N} 2^{j+1}\left(a_j^2 - b_j^2\right)},\quad a_0 = 1,\ b_0 = \frac{1}{\sqrt{2}}$$
An iteration built on the arithmetic–geometric mean: the number of correct digits doubles at every step, so four steps give more than 19 digits.

### 109. Euler's ζ(4) {#pi-zeta-4}
$$\zeta(4) = \sum_{n=1}^{\infty} \frac{1}{n^4} = \frac{\pi^4}{90}$$
Euler's evaluation of the zeta function at 4, giving $\pi = \left(90\,\zeta(4)\right)^{1/4}$; it converges much faster than the Basel sum (#11).

### 110. The Gaussian integral {#pi-gaussian-integral}
$$\int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}$$
The area under the bell curve; it follows from the polar-coordinate trick $\iint e^{-(x^2+y^2)}\,dx\,dy = \pi$ and underlies the normal distribution.

### 111. Area of a circle {#pi-circle-area}
$$A = \pi r^2$$
The area enclosed by a circle of radius $r$; Archimedes squeezed it between inscribed and circumscribed polygons.

### 112. Circumference of a circle {#pi-circle-circumference}
$$C = 2\pi r$$
The definition of $\pi$ as the ratio of the circumference to the diameter, $\pi = C/d$.

### 113. Buffon's needle {#pi-buffon-s-needle}
$$P = \frac{2\ell}{\pi t}\qquad (\ell \le t)$$
The probability that a needle of length $\ell$ dropped on lines spaced $t$ apart crosses one; counting crossings estimates $\pi$.

### 114. Monte Carlo estimate of π {#pi-monte-carlo-quarter-circle}
$$\pi \approx 4\,\frac{N_{\text{inside}}}{N_{\text{total}}}$$
Random points in the unit square fall inside the quarter circle with probability $\pi/4$; the error shrinks like $1/\sqrt{N}$.

### 115. Stirling's √(2π) {#pi-stirling-sqrt-2pi}
$$\lim_{n\to\infty} \frac{n!}{\sqrt{n}\,(n/e)^n} = \sqrt{2\pi}$$
The constant in Stirling's approximation, proved by combining Wallis's product with the Gaussian integral.

### 116. Gregory's arctangent series {#pi-gregory-arctan-series}
$$\arctan x = \sum_{k=0}^{\infty} \frac{(-1)^k\,x^{2k+1}}{2k+1}\quad(|x|\le 1),\qquad \arctan 1 = \frac{\pi}{4}$$
James Gregory's power series for the inverse tangent; at $x = 1$ it gives the Leibniz series and for small $x$ it is the engine of Machin-type formulas.

### 117. Newton–Euler arcsine series {#pi-euler-arcsin-series}
$$\arcsin x = \sum_{k=0}^{\infty} \frac{(2k)!}{4^k\,(k!)^2\,(2k+1)}\,x^{2k+1},\qquad \frac{\pi}{6} = \arcsin\frac{1}{2}$$
Newton's series for the inverse sine; putting $x = 1/2$ gives $\pi/6$ with a geometric rate of convergence.

### 118. Lambert's continued fraction {#pi-lambert-continued-fraction}
$$\tan x = \cfrac{x}{1 - \cfrac{x^2}{3 - \cfrac{x^2}{5 - \cfrac{x^2}{7 - \cdots}}}}$$
The continued fraction with which Lambert proved in 1761 that $\pi$ is irrational: for rational $x \ne 0$ the value cannot be rational, yet $\tan(\pi/4) = 1$.

### 119. Ramanujan–Sato series {#pi-ramanujan-sato-series}
$$\frac{1}{\pi} = \frac{1}{16}\sum_{k=0}^{\infty} \frac{(42k+5)\binom{2k}{k}^3}{2^{12k}}$$
One of Ramanujan's series for $1/\pi$ that Sato's theory explains: rational terms built on the central binomial coefficients, each adding almost two correct digits.

### 120. Euler's arctangent identity {#pi-euler-arctan-identity}
$$\frac{\pi}{4} = \arctan\frac{1}{2} + \arctan\frac{1}{3}$$
A special case of $\arctan a + \arctan\frac{1-a}{1+a} = \frac{\pi}{4}$, the kind of identity from which Machin-like formulas are built.

### 121. Euler's sine product {#pi-euler-sine-product}
$$\sin \pi x = \pi x \prod_{n=1}^{\infty}\left(1 - \frac{x^2}{n^2}\right)$$
Euler's factorisation of the sine over its zeros; comparing coefficients gives the Basel sum and, at $x = 1/2$, Wallis's product.

### 122. Γ(1/2) = √π {#pi-gamma-half}
$$\Gamma\!\left(\tfrac{1}{2}\right) = \sqrt{\pi}$$
The value of the gamma function at one half, equivalent to the Gaussian integral and the root of the $\pi$ in many probability and physics formulas.

### 123. Euler's reflection formula {#pi-reflection-formula}
$$\Gamma(z)\,\Gamma(1-z) = \frac{\pi}{\sin \pi z}$$
Links the gamma function at $z$ and $1-z$ to $\pi$; at $z = 1/2$ it gives $\Gamma(1/2)^2 = \pi$.

### 124. Archimedes' polygon bounds {#pi-archimedes-polygons}
$$n\sin\frac{\pi}{n} < \pi < n\tan\frac{\pi}{n}$$
Half the perimeter of the inscribed and circumscribed regular $n$-gons brackets $\pi$; Archimedes used $n = 96$ to show $223/71 < \pi < 22/7$.

### 125. Madhava's series {#pi-madhava-series}
$$\pi = \sqrt{12}\sum_{k=0}^{\infty} \frac{(-3)^{-k}}{2k+1} = \sqrt{12}\left(1 - \frac{1}{3\cdot 3} + \frac{1}{5\cdot 3^2} - \cdots\right)$$
Madhava of Sangamagrama's 14th-century series from $\arctan(1/\sqrt{3}) = \pi/6$; the ratio $-1/3$ makes it converge geometrically.
