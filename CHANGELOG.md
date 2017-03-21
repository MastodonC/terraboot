# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

## [0.4.6] - 2017-03-22

- BREAKING CHANGE: Removed hardcoded Logstash AMI (it breaks things in
  different regions) and replaced with mesos-ami (not ideally named).  Your projects `terraboot-$name` will have to update it's `infra.clj` or equivalent with any calls made to `(vpc-vpn-infra ..)` now to include in the argument hash`:mesos-ami mesos-ami`.

```
(condp = target
      "vpc"  (do
               (to-file
                (vpc-vpn-infra
                 {
                  :vpc-name vpc-name
                  :region region
                  :azs [:a :b]
                  :default-ami default-ami
                  :mesos-ami mesos-ami
                  ...
```

## Unknown
### Changed
- Add a new arity to `make-widget-async` to provide a different widget shape.

## [0.1.1] - 2016-02-12
### Changed
- Documentation on how to make the widgets.

### Removed
- `make-widget-sync` - we're all async, all the time.

### Fixed
- Fixed widget maker to keep working when daylight savings switches over.

## 0.1.0 - 2016-02-12
### Added
- Files from the new template.
- Widget maker public API - `make-widget-sync`.

[unreleased]: https://github.com/your-name/terraboot/compare/0.1.1...HEAD
[0.1.1]: https://github.com/your-name/terraboot/compare/0.1.0...0.1.1
