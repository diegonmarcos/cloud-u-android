# The 100 Most Important Formulas in Mathematics and Science

---

## 1. Pure Mathematics, Geometry & Number Theory

### 1. The Pythagorean Theorem
$$a^2 + b^2 = c^2$$
Defines the fundamental relationship between the sides of a right triangle in Euclidean geometry, serving as the basis for distance metrics, Euclidean vectors, and trigonometry.

### 2. Euler's Identity
$$e^{i\pi} + 1 = 0$$
Widely celebrated as one of the most beautiful equations in mathematics, it unifies the five fundamental constants ($e$, $i$, $\pi$, $1$, and $0$) using addition, multiplication, and exponentiation.

### 3. Euler's Formula
$$e^{i\theta} = \cos\theta + i\sin\theta$$
Establishes the fundamental bridge between trigonometric functions and complex exponential functions, underpinning signal processing and Fourier analysis.

### 4. The Quadratic Formula
$$x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}$$
Provides the exact analytical solution to the roots of any quadratic equation of the form $ax^2 + bx + c = 0$.

### 5. The Binomial Theorem
$$(x + y)^n = \sum_{k=0}^n \binom{n}{k} x^{n-k} y^k$$
Describes the algebraic expansion of powers of a binomial sum into a series using binomial coefficients.

### 6. Euler’s Polyhedral Formula
$$V - E + F = 2$$
Relates the number of vertices ($V$), edges ($E$), and faces ($F$) of any convex polyhedron, forming the foundation of combinatorial topology.

### 7. The Law of Cosines
$$c^2 = a^2 + b^2 - 2ab\cos C$$
Generalizes the Pythagorean theorem to any arbitrary triangle, relating side lengths to the cosine of one of the angles.

### 8. The Law of Sines
$$\frac{a}{\sin A} = \frac{b}{\sin B} = \frac{c}{\sin C} = 2R$$
Relates the sides of an arbitrary triangle to the sines of their opposing angles and the radius $R$ of its circumscribed circle.

### 9. Heron's Formula
$$A = \sqrt{s(s - a)(s - b)(s - c)}$$
Calculates the area ($A$) of any triangle from its three side lengths ($a, b, c$), where $s = \frac{a+b+c}{2}$ is the semi-perimeter.

### 10. The Prime Number Theorem
$$\pi(x) \sim \frac{x}{\ln x}$$
Describes the asymptotic distribution of prime numbers among positive integers, stating that the number of primes less than $x$ approaches $x/\ln x$ as $x \to \infty$.

### 11. The Basel Problem (Euler's Sum)
$$\sum_{n=1}^\infty \frac{1}{n^2} = \frac{\pi^2}{6}$$
Solved by Leonhard Euler in 1734, revealing an unexpected connection between reciprocal squares of integers and $\pi$.

### 12. The Riemann Zeta Function
$$\zeta(s) = \sum_{n=1}^\infty \frac{1}{n^s} = \prod_{p \text{ prime}} \frac{1}{1 - p^{-s}}$$
Relates the summation over all integers to the Euler product over all prime numbers, central to modern analytic number theory.

### 13. De Moivre’s Formula
$$(\cos\theta + i\sin\theta)^n = \cos(n\theta) + i\sin(n\theta)$$
Allows powers of complex numbers in polar form to be computed directly via angle multiplication.

### 14. Stirling's Approximation
$$n! \approx \sqrt{2\pi n} \left(\frac{n}{e}\right)^n$$
Provides an asymptotic estimate for factorials of large numbers, essential in combinatorics and statistical physics.

### 15. The Chudnovsky Algorithm for $\pi$
$$\frac{1}{\pi} = 12 \sum_{k=0}^\infty \frac{(-1)^k (6k)! (545140134k + 13591409)}{(3k)! (k!)^3 (640320)^{3k + 3/2}}$$
An ultra-fast converging hypergeometric series used by modern supercomputers to compute trillions of digits of $\pi$.

---

## 2. Analysis, Calculus & Differential Equations

### 16. The Fundamental Theorem of Calculus
$$\int_a^b f'(x)\,dx = f(b) - f(a)$$
Links differentiation and definite integration as inverse operations, enabling the analytical calculation of accumulated areas and quantities.

### 17. The Formal Definition of the Derivative
$$f'(x) = \lim_{h \to 0} \frac{f(x + h) - f(x)}{h}$$
Defines the instantaneous rate of change of a continuous function at a given point as the limit of a difference quotient.

### 18. Integration by Parts
$$\int u\,dv = uv - \int v\,du$$
Derived from the product rule of differentiation, allowing the integration of products of functions.

### 19. Taylor Series Expansion
$$f(x) = \sum_{n=0}^\infty \frac{f^{(n)}(a)}{n!} (x - a)^n$$
Represents any infinitely differentiable function as an infinite polynomial expansion evaluated around a reference point $a$.

### 20. The Continuous Fourier Transform
$$\hat{f}(\xi) = \int_{-\infty}^\infty f(x) e^{-2\pi i x \xi}\,dx$$
Decomposes a time-domain or spatial-domain function into its constituent frequency components.

### 21. The Inverse Fourier Transform
$$f(x) = \int_{-\infty}^\infty \hat{f}(\xi) e^{2\pi i x \xi}\,d\xi$$
Reconstructs the original time-domain function from its continuous frequency spectrum.

### 22. The Laplace Transform
$$\mathcal{L}\{f(t)\} = F(s) = \int_0^\infty f(t) e^{-st}\,dt$$
Transforms linear ordinary differential equations in the time domain into simpler algebraic equations in the complex frequency domain.

### 23. The Cauchy-Schwarz Inequality
$$|\langle \mathbf{u}, \mathbf{v} \rangle|^2 \le \langle \mathbf{u}, \mathbf{u} \rangle \langle \mathbf{v}, \mathbf{v} \rangle$$
A foundational inequality in linear algebra and functional analysis bounding the inner product of two vectors by their norms.

### 24. Cauchy's Integral Formula
$$f(a) = \frac{1}{2\pi i} \oint_\gamma \frac{f(z)}{z - a}\,dz$$
Shows that for a holomorphic complex function, its value inside a closed contour is completely determined by values on the boundary.

### 25. Cauchy's Residue Theorem
$$\oint_\gamma f(z)\,dz = 2\pi i \sum_k \operatorname{Res}(f, a_k)$$
A powerful contour integration tool that evaluates complex line integrals by calculating local residues at interior singularities.

### 26. The Divergence (Gauss-Ostrogradsky) Theorem
$$\iiint_V (\nabla \cdot \mathbf{F})\,dV = \iint_{\partial V} (\mathbf{F} \cdot \mathbf{n})\,dS$$
Relates the volume integral of the divergence of a vector field to the surface flux through its closed boundary.

### 27. Stokes' Theorem
$$\iint_S (\nabla \times \mathbf{F}) \cdot d\mathbf{S} = \oint_{\partial S} \mathbf{F} \cdot d\mathbf{r}$$
Relates the surface integral of the curl of a vector field over an open surface to the circulation along the boundary curve.

### 28. Green’s Theorem
$$\oint_C (L\,dx + M\,dy) = \iint_D \left(\frac{\partial M}{\partial x} - \frac{\partial L}{\partial y}\right) dA$$
A two-dimensional special case of Stokes' theorem relating line integrals around a plane curve to double integrals over the enclosed region.

### 29. The Heat Equation
$$\frac{\partial u}{\partial t} = \alpha \nabla^2 u$$
A parabolic partial differential equation governing the diffusion of thermal energy and passive particles over time.

### 30. The Classical Wave Equation
$$\frac{\partial^2 u}{\partial t^2} = v^2 \nabla^2 u$$
A hyperbolic partial differential equation describing the propagation of mechanical, acoustic, and electromagnetic waves through a medium at speed $v$.

---

## 3. Probability, Statistics & Information Theory

### 31. Bayes' Theorem
$$P(A \mid B) = \frac{P(B \mid A) P(A)}{P(B)}$$
Calculates conditional probability by updating the prior belief $P(A)$ in light of observed evidence $B$.

### 32. The Normal (Gaussian) Distribution
$$f(x) = \frac{1}{\sigma \sqrt{2\pi}} \exp\left(-\frac{(x - \mu)^2}{2\sigma^2}\right)$$
Defines the symmetric bell-shaped probability density function with mean $\mu$ and standard deviation $\sigma$.

### 33. The Central Limit Theorem
$$\frac{\bar{X}_n - \mu}{\sigma / \sqrt{n}} \xrightarrow{d} \mathcal{N}(0, 1) \quad \text{as } n \to \infty$$
States that the normalized sum of independent and identically distributed random variables tends toward a standard normal distribution.

### 34. The Poisson Distribution
$$P(X = k) = \frac{\lambda^k e^{-\lambda}}{k!}$$
Models the probability of $k$ independent events occurring within a fixed interval given a known average rate $\lambda$.

### 35. The Binomial Distribution
$$P(X = k) = \binom{n}{k} p^k (1 - p)^{n - k}$$
Calculates the probability of observing exactly $k$ successes out of $n$ independent Bernoulli trials with probability $p$.

### 36. Variance of a Random Variable
$$\operatorname{Var}(X) = \mathbb{E}[(X - \mu)^2] = \mathbb{E}[X^2] - (\mathbb{E}[X])^2$$
Measures the dispersion or spread of values of a random variable around its mathematical expectation $\mu$.

### 37. Covariance
$$\operatorname{Cov}(X, Y) = \mathbb{E}[(X - \mathbb{E}[X])(Y - \mathbb{E}[Y])]$$
Quantifies the joint directional variability of two distinct random variables.

### 38. Pearson Correlation Coefficient
$$\rho_{X, Y} = \frac{\operatorname{Cov}(X, Y)}{\sigma_X \sigma_Y}$$
Measures the strength and direction of linear association between two variables, normalized between $-1$ and $+1$.

### 39. Shannon Entropy
$$H(X) = -\sum_{i=1}^n P(x_i) \log_2 P(x_i)$$
Quantifies the fundamental limit of expected information content, uncertainty, and lossless compression rate for a discrete source.

### 40. Kullback-Leibler Divergence
$$D_{\text{KL}}(P \parallel Q) = \sum_{x \in \mathcal{X}} P(x) \log\left(\frac{P(x)}{Q(x)}\right)$$
Measures the relative statistical distance and information loss when approximating true distribution $P$ with reference distribution $Q$.

### 41. Chebyshev's Inequality
$$P(|X - \mu| \ge k\sigma) \le \frac{1}{k^2}$$
Guarantees an upper bound on the probability that a random variable deviates by more than $k$ standard deviations from the mean.

### 42. Weak Law of Large Numbers
$$\lim_{n \to \infty} P\left(\left|\frac{1}{n}\sum_{i=1}^n X_i - \mu\right| \ge \varepsilon\right) = 0$$
States that the sample average of independent trials converges in probability toward the theoretical population mean $\mu$.

### 43. The Black-Scholes Equation
$$\frac{\partial V}{\partial t} + \frac{1}{2}\sigma^2 S^2 \frac{\partial^2 V}{\partial S^2} + rS \frac{\partial V}{\partial S} - rV = 0$$
A partial differential equation modeling the fair financial pricing of European derivative options over time.

---

## 4. Classical Mechanics & Gravitation

### 44. Newton's Second Law of Motion
$$\mathbf{F} = \frac{d\mathbf{p}}{dt} = m\mathbf{a}$$
Relates net applied force ($\mathbf{F}$) to the time rate of change of linear momentum ($\mathbf{p}$), or mass times acceleration for invariant mass.

### 45. Newton’s Law of Universal Gravitation
$$F = G \frac{m_1 m_2}{r^2}$$
Describes the inverse-square gravitational attraction between two point masses separated by distance $r$.

### 46. Kinetic Energy
$$E_k = \frac{1}{2}mv^2 = \frac{p^2}{2m}$$
Quantifies the mechanical work stored in an object of mass $m$ by virtue of its velocity $v$ or momentum $p$.

### 47. Gravitational Potential Energy (Uniform Field)
$$U = mgh$$
Measures potential energy gained by lifting mass $m$ to height $h$ in a uniform gravitational field $g$.

### 48. General Gravitational Potential Energy
$$U(r) = -G \frac{M m}{r}$$
Describes the potential energy of a two-body system separated by distance $r$, set to zero at infinite separation.

### 49. Hooke's Law
$$F = -kx$$
Models linear restoring force exerted by an ideal spring stretched or compressed by displacement $x$.

### 50. Simple Harmonic Angular Frequency
$$\omega = \sqrt{\frac{k}{m}}$$
Defines the natural angular frequency of oscillation for an undamped mass-spring oscillator.

### 51. Period of a Simple Pendulum
$$T = 2\pi \sqrt{\frac{L}{g}}$$
Approximates the small-amplitude period of oscillation for a pendulum of length $L$ in gravitational field $g$.

### 52. Kepler's Third Law (Harmonic Law)
$$\frac{T^2}{a^3} = \frac{4\pi^2}{G(M + m)}$$
Relates orbital period ($T$) squared to semi-major axis ($a$) cubed for elliptical orbits around a central mass.

### 53. The Work-Energy Theorem
$$W_{\text{net}} = \Delta K = \int_{\mathbf{r}_1}^{\mathbf{r}_2} \mathbf{F}_{\text{net}} \cdot d\mathbf{r}$$
Demonstrates that the net work done by all forces on a body equals the net change in its kinetic energy.

### 54. The Euler-Lagrange Equation
$$\frac{d}{dt}\left(\frac{\partial L}{\partial \dot{q}_i}\right) - \frac{\partial L}{\partial q_i} = 0$$
Forms the cornerstone of analytical Lagrangian mechanics, identifying paths of stationary action for generalized coordinates $q_i$.

### 55. Hamilton's Canonical Equations
$$\dot{q}_i = \frac{\partial H}{\partial p_i}, \qquad \dot{p}_i = -\frac{\partial H}{\partial q_i}$$
Replaces second-order Lagrangian equations with a coupled set of first-order differential equations in phase space $(q, p)$.

---

## 5. Thermodynamics & Statistical Mechanics

### 56. The First Law of Thermodynamics
$$dU = \delta Q - \delta W$$
Expresses conservation of energy: internal energy change ($dU$) equals heat added ($\delta Q$) minus thermodynamic work done ($\delta W$).

### 57. The Second Law of Thermodynamics (Clausius Inequality)
$$dS \ge \frac{\delta Q}{T}$$
States that the entropy ($S$) of an isolated physical system never decreases over time during spontaneous processes.

### 58. Boltzmann’s Entropy Formula
$$S = k_B \ln \Omega$$
Bridges microscopic states and macroscopic thermodynamics, connecting thermodynamic entropy to the number of microstates $\Omega$.

### 59. The Ideal Gas Law
$$PV = nRT = N k_B T$$
Equation of state describing an idealized non-interacting gas in terms of pressure, volume, temperature, and substance quantity.

### 60. Carnot Engine Thermal Efficiency
$$\eta = 1 - \frac{T_C}{T_H}$$
Sets the theoretical maximum reversible thermodynamic efficiency for any heat engine operating between hot ($T_H$) and cold ($T_C$) reservoirs.

### 61. Maxwell-Boltzmann Speed Distribution
$$f(v) = 4\pi \left(\frac{m}{2\pi k_B T}\right)^{3/2} v^2 \exp\left(-\frac{mv^2}{2k_B T}\right)$$
Gives the statistical probability density of molecular speeds for an ideal gas at thermodynamic temperature $T$.

### 62. Gibbs Free Energy
$$G = H - TS$$
Thermodynamic potential used to predict chemical reaction spontaneity and equilibrium at constant temperature and pressure.

### 63. Enthalpy Definition
$$H = U + PV$$
Quantifies the total heat content of a thermodynamic system, incorporating internal energy ($U$) and flow work ($PV$).

### 64. The Stefan-Boltzmann Law
$$j^* = \sigma T^4$$
States that total radiant energy emitted per unit surface area of a blackbody is proportional to the fourth power of absolute temperature.

### 65. Planck’s Law of Blackbody Radiation
$$B_\lambda(\lambda, T) = \frac{2hc^2}{\lambda^5} \frac{1}{\exp\left(\frac{hc}{\lambda k_B T}\right) - 1}$$
Accurately predicts spectral radiation density without the classical ultraviolet catastrophe, initiating quantum mechanics.

---

## 6. Electromagnetism & Classical Optics

### 66. Gauss's Law (Electrostatics)
$$\nabla \cdot \mathbf{E} = \frac{\rho}{\varepsilon_0}$$
States that the divergence of the electric field equals local volume charge density divided by vacuum permittivity.

### 67. Gauss's Law for Magnetism
$$\nabla \cdot \mathbf{B} = 0$$
Expresses the non-existence of isolated magnetic monopoles; magnetic field lines form continuous closed loops.

### 68. Faraday’s Law of Electromagnetic Induction
$$\nabla \times \mathbf{E} = -\frac{\partial \mathbf{B}}{\partial t}$$
States that a time-varying magnetic field induces a spatially circulating non-conservative electric field.

### 69. The Ampère-Maxwell Law
$$\nabla \times \mathbf{B} = \mu_0 \mathbf{J} + \mu_0 \varepsilon_0 \frac{\partial \mathbf{E}}{\partial t}$$
Shows that magnetic fields are produced by conduction current densities ($\mathbf{J}$) and Maxwell's displacement currents.

### 70. The Lorentz Force Law
$$\mathbf{F} = q(\mathbf{E} + \mathbf{v} \times \mathbf{B})$$
Calculates the total electromagnetic force acting on a particle carrying electric charge $q$ moving with velocity $\mathbf{v}$.

### 71. Coulomb’s Law
$$F = \frac{1}{4\pi\varepsilon_0} \frac{q_1 q_2}{r^2}$$
Quantifies the electrostatic force between two stationary electric point charges separated by distance $r$.

### 72. Ohm's Law (Microscopic and Macroscopic)
$$\mathbf{J} = \sigma \mathbf{E} \quad \Longleftrightarrow \quad V = IR$$
Relates electric potential difference ($V$) across a conductor to current ($I$) and electrical resistance ($R$).

### 73. Snell’s Law of Refraction
$$n_1 \sin\theta_1 = n_2 \sin\theta_2$$
Describes the angular bending of light rays passing across the interface between two media of differing refractive indices.

### 74. The Poynting Vector
$$\mathbf{S} = \frac{1}{\mu_0} (\mathbf{E} \times \mathbf{B})$$
Represents the directional energy flux density (power per unit area) transported by an electromagnetic wave.

### 75. Speed of Light from Electromagnetic Constants
$$c = \frac{1}{\sqrt{\varepsilon_0 \mu_0}}$$
Directly proves that light is an electromagnetic wave whose speed is fixed by electric permittivity and magnetic permeability.

---

## 7. Special & General Relativity

### 76. Mass-Energy Equivalence
$$E = mc^2$$
Asserts that mass and rest energy are directly proportional and interchangeable via the speed of light squared.

### 77. Relativistic Energy-Momentum Relation
$$E^2 = (pc)^2 + (m_0 c^2)^2$$
Calculates total relativistic energy ($E$) for particles with rest mass $m_0$ and momentum $p$, including massless photons ($E = pc$).

### 78. The Lorentz Factor
$$\gamma = \frac{1}{\sqrt{1 - \frac{v^2}{c^2}}}$$
Dimensionless scale factor by which time, length, and relativistic mass scale as relative speed $v$ approaches $c$.

### 79. Relativistic Time Dilation
$$\Delta t' = \gamma \Delta t = \frac{\Delta t}{\sqrt{1 - \frac{v^2}{c^2}}}$$
Shows that a clock moving at speed $v$ ticks slower relative to a stationary observer's reference frame.

### 80. Relativistic Length Contraction
$$L = \frac{L_0}{\gamma} = L_0 \sqrt{1 - \frac{v^2}{c^2}}$$
Demonstrates that the physical length of an object appears contracted along its direction of travel at relativistic speeds.

### 81. Einstein's Field Equations of General Relativity
$$G_{\mu\nu} + \Lambda g_{\mu\nu} = \frac{8\pi G}{c^4} T_{\mu\nu}$$
Relates the geometry and curvature of spacetime ($G_{\mu\nu}$) to the distribution of matter and energy ($T_{\mu\nu}$).

### 82. The Schwarzschild Radius
$$r_s = \frac{2GM}{c^2}$$
Calculates the radius defining the event horizon of a non-rotating spherically symmetric black hole of mass $M$.

### 83. The Spacetime Invariant Interval
$$ds^2 = -c^2 dt^2 + dx^2 + dy^2 + dz^2$$
Defines the invariant distance between two events in flat Minkowski spacetime, invariant across all inertial reference frames.

---

## 8. Quantum Mechanics & Modern Physics

### 84. The Planck-Einstein Relation
$$E = h\nu = \hbar\omega$$
States that the energy of a photon is directly quantized in discrete packets proportional to its electromagnetic frequency.

### 85. The de Broglie Wavelength
$$\lambda = \frac{h}{p}$$
Establishes wave-particle duality, showing that every moving physical particle with momentum $p$ possesses a characteristic wavelength.

### 86. The Time-Dependent Schrödinger Equation
$$i\hbar \frac{\partial}{\partial t}\Psi(\mathbf{r}, t) = \hat{H}\Psi(\mathbf{r}, t)$$
Governs the deterministic evolution of the quantum wave function $\Psi$ over time under Hamiltonian operator $\hat{H}$.

### 87. The Time-Independent Schrödinger Equation
$$\hat{H}\psi = E\psi$$
An eigenvalue problem determining stationary quantum energy states ($E$) and spatial wavefunctions ($\psi$).

### 88. Heisenberg’s Uncertainty Principle
$$\sigma_x \sigma_p \ge \frac{\hbar}{2}$$
Sets the fundamental quantum mechanical bound on the simultaneous measurement precision of position and momentum.

### 89. The Dirac Equation
$$(i\gamma^\mu \partial_\mu - m)\psi = 0$$
Unifies quantum mechanics with special relativity for spin-$1/2$ fermions, predicting antimatter and intrinsic spin.

### 90. Bohr Orbit Radius
$$r_n = \frac{4\pi\varepsilon_0 \hbar^2}{m_e e^2} n^2$$
Calculates the quantized radius of stable electron orbits in hydrogen-like atoms for principal quantum number $n$.

### 91. The Rydberg Formula
$$\frac{1}{\lambda} = R_\infty \left(\frac{1}{n_1^2} - \frac{1}{n_2^2}\right)$$
Predicts wavelengths of spectral emission lines caused by electron transitions between discrete energy shells in hydrogen.

### 92. The Fermi-Dirac Distribution
$$\bar{n}_i = \frac{1}{\exp\left(\frac{\epsilon_i - \mu}{k_B T}\right) + 1}$$
Describes statistical energy level occupation for identical fermions obeying the Pauli exclusion principle.

---

## 9. Fluid Dynamics, Chemistry, Cosmology & Nonlinear Systems

### 93. The Incompressible Navier-Stokes Equations
$$\rho\left(\frac{\partial \mathbf{u}}{\partial t} + \mathbf{u} \cdot \nabla \mathbf{u}\right) = -\nabla p + \mu \nabla^2 \mathbf{u} + \mathbf{f}$$
Governs the momentum conservation and fluid motion for viscous, incompressible Newtonian fluid flows.

### 94. The Fluid Continuity Equation
$$\frac{\partial \rho}{\partial t} + \nabla \cdot (\rho \mathbf{u}) = 0$$
Expresses the local conservation of mass within a continuous fluid or compressible medium.

### 95. Bernoulli's Principle
$$p + \frac{1}{2}\rho v^2 + \rho gh = \text{constant}$$
Expresses conservation of mechanical energy along a streamline for steady, frictionless, incompressible fluid flow.

### 96. The Arrhenius Equation
$$k = A \exp\left(-\frac{E_a}{RT}\right)$$
Models the temperature dependence of chemical reaction rate constants based on activation energy $E_a$.

### 97. The Nernst Equation
$$E = E^\circ - \frac{RT}{zF} \ln Q$$
Determines the equilibrium reduction potential of an electrochemical cell as a function of temperature and reaction quotient $Q$.

### 98. The Hubble-Lemaître Law
$$v = H_0 d$$
Describes the cosmological expansion of the universe, relating recessional velocity ($v$) of galaxies to physical distance ($d$).

### 99. The First Friedmann Equation
$$\left(\frac{\dot{a}}{a}\right)^2 = \frac{8\pi G}{3}\rho - \frac{kc^2}{a^2} + \frac{\Lambda c^2}{3}$$
Derives the expansion rate of homogeneous, isotropic universes from general relativity, where $a(t)$ is the scale factor.

### 100. The Logistic Map
$$x_{n+1} = r x_n (1 - x_n)$$
A canonical nonlinear polynomial mapping exhibiting period-doubling routes to deterministic chaos in dynamic systems.